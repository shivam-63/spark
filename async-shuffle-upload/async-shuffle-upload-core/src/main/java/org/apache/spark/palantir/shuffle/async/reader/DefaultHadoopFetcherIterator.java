/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.palantir.shuffle.async.reader;

import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.common.base.Function;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.palantir.shuffle.async.FetchFailedExceptionThrower;
import org.apache.spark.palantir.shuffle.async.client.ShuffleClient;
import org.apache.spark.palantir.shuffle.async.immutables.ImmutablesStyle;
import org.apache.spark.palantir.shuffle.async.metrics.HadoopFetcherIteratorMetrics;
import org.apache.spark.palantir.shuffle.async.util.Suppliers;
import org.apache.spark.shuffle.FetchFailedException;
import org.apache.spark.shuffle.api.ShuffleBlockInfo;
import org.apache.spark.shuffle.api.ShuffleBlockInputStream;
import org.apache.spark.storage.BlockId;
import org.apache.spark.storage.BlockManagerId;
import org.apache.spark.storage.ShuffleBlockId;

/**
 * Implementation of {@link HadoopFetcherIterator} that submits all requests to fetch blocks
 * immediately, then waits for the first available result to return in each call to {@link #next()}.
 * <p>
 * Download requests are immediately pushed to the backing
 * {@link org.apache.spark.palantir.shuffle.async.client.basic.HadoopShuffleClient} in
 * {@link #fetchDataFromHadoop()}. As each result is returned from the shuffle client, it is pushed
 * onto a blocking queue. Calls to {@link #next} block on a result to become available. Thus this
 * implementation is a classic case of the producer-consumer paradigm.
 * <p>
 * Note that we don't block with a time out when popping results from the queue, as downloads are
 * eager and can be time-consuming. Unfortunately, that means that if all download tasks get stuck
 * for some reason, we can end up blocking forever. We mitigate this by also pushing error results
 * onto the queue - if a download fails, the queue is given a failure result instead of a success
 * result, and attempting to use the error event's input stream results in a thrown
 * {@link FetchFailedException}.
 */
public final class DefaultHadoopFetcherIterator implements HadoopFetcherIterator {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultHadoopFetcherIterator.class);

  private final BlockingQueue<BlockDataResult> fetchResults;
  private final ShuffleClient client;
  private final AtomicInteger streamCount;
  private final HadoopFetcherIteratorMetrics metrics;

  private final Collection<ShuffleBlockInfo> blocksToFetch;
  private final Map<ShuffleBlockId, ListenableFuture<BlockDataResult>> activeFetchRequests;

  public DefaultHadoopFetcherIterator(
      ShuffleClient client,
      Collection<ShuffleBlockInfo> blocksToFech,
      HadoopFetcherIteratorMetrics metrics) {
    this.fetchResults = Queues.newLinkedBlockingQueue();
    this.activeFetchRequests = new ConcurrentHashMap<>();
    this.client = client;
    this.streamCount = new AtomicInteger();
    this.metrics = metrics;
    this.blocksToFetch = blocksToFech;
  }

  void fetchDataFromHadoop() {
    // Find all blocks that were not yet successfully fetched from the executors.
    blocksToFetch.forEach(shuffleBlockInfo -> {
      // Request all remaining blocks from remote storage.
      // Not necessarily 100% accurate - for example, there may be other executors
      // remaining where we could have fetched the data from. But:

      // 1. The lifecycle of using the underlying iterator that fetches from other
      //    executors, is more or less unpredictable after a fetch failure. And,
      // 2. It's better to go to remote storage excessively, than attempting to fetch blocks
      //    from any executors that might be down at all - since attempting to fetch from
      //    dead executors requires waiting for a costly timeout
      fetchBlock(shuffleBlockInfo);
      metrics.markFetchFromExecutorFailed(
          shuffleBlockInfo.getShuffleId(),
          shuffleBlockInfo.getMapId(),
          shuffleBlockInfo.getReduceId(),
          shuffleBlockInfo.getMapTaskAttemptId());
    });
  }

  private void fetchBlock(ShuffleBlockInfo shuffleBlockInfo) {
    int shuffleId = shuffleBlockInfo.getShuffleId();
    int mapId = shuffleBlockInfo.getMapId();
    int reduceId = shuffleBlockInfo.getReduceId();
    long attemptId = shuffleBlockInfo.getMapTaskAttemptId();

    streamCount.incrementAndGet();
    ShuffleBlockId shuffleBlockId = ShuffleBlockId.apply(shuffleId, mapId, reduceId);
    ListenableFuture<BlockDataResult> fetchFuture =
        Futures.withFallback(
            Futures.transform(client.getBlockData(shuffleId, mapId, reduceId, attemptId),
                (Function<Supplier<InputStream>, BlockDataResult>) stream -> {
                  LOG.info("Successfully opened input stream for map output from remote storage.",
                      SafeArg.of("shuffleId", shuffleId),
                      SafeArg.of("mapId", mapId),
                      SafeArg.of("reduceId", reduceId),
                      SafeArg.of("attemptId", attemptId));
                  metrics.markFetchFromRemoteSucceeded(shuffleId, mapId, reduceId, attemptId);
                  return BlockDataSuccessResult.of(Suppliers.compose(
                      stream,
                      resolvedStream ->
                          new ShuffleBlockInputStream(shuffleBlockId, resolvedStream)));
                }),
            error -> {
              LOG.error("Failed to get stream for map output from remote storage.",
                  SafeArg.of("shuffleId", shuffleId),
                  SafeArg.of("mapId", mapId),
                  SafeArg.of("reduceId", reduceId),
                  SafeArg.of("attemptId", attemptId),
                  error);
              metrics.markFetchFromRemoteFailed(shuffleId, mapId, reduceId, attemptId);
              return Futures.immediateFuture(
                  BlockDataErrorResult.builder()
                      .error(error)
                      .shuffleBlockId(shuffleBlockId)
                      .blockManagerId(shuffleBlockInfo.getShuffleLocation().get())
                      .build());
            });
    activeFetchRequests.put(shuffleBlockId, fetchFuture);
    Futures.addCallback(fetchFuture, new FutureCallback<BlockDataResult>() {

      @Override
      public void onSuccess(BlockDataResult result) {
        fetchResults.add(result);
        LOG.debug("Successfully added block data result to queue.");
      }

      @Override
      public void onFailure(Throwable error) {
        LOG.error("Failed to create block data result.", error);
      }
    });
  }

  @Override
  public void cleanup() {
    // cancel all active things
    LOG.info("Cleaning up the DefaultHadoopFetcherIterator");
    AtomicInteger numFuturesCancelled = new AtomicInteger();
    activeFetchRequests.values().forEach(future -> {
      if (!future.isDone()) {
        future.cancel(true);
        numFuturesCancelled.addAndGet(1);
      }
    });
    blocksToFetch.forEach(block -> client.deleteDownloadedBlockData(
        block.getShuffleId(), block.getMapId(), block.getReduceId(), block.getMapTaskAttemptId()));
  }

  @Override
  public boolean hasNext() {
    return streamCount.get() > 0;
  }

  @Override
  public ShuffleBlockInputStream next() {
    if (streamCount.getAndDecrement() > 0) {
      try {
        BlockDataResult result = fetchResults.take();
        activeFetchRequests.remove(result.getShuffleBlockId());
        return result.getResult().get();
      } catch (InterruptedException e) {
        LOG.error("Error encountered while waiting for InputStream to become available", e);
        throw new SafeRuntimeException(e);
      }
    }
    throw new SafeRuntimeException("Next should not be called because iterator is empty");
  }

  interface BlockDataResult {
    ShuffleBlockId getShuffleBlockId();

    Supplier<ShuffleBlockInputStream> getResult();
  }

  @Value.Immutable
  @ImmutablesStyle
  abstract static class BlockDataSuccessResult implements BlockDataResult {
    abstract Supplier<ShuffleBlockInputStream> blockDataStream();

    @Override
    public ShuffleBlockId getShuffleBlockId() {
      return toShuffleBlockId(blockDataStream().get().getBlockId());
    }

    @Override
    public final Supplier<ShuffleBlockInputStream> getResult() {
      return blockDataStream();
    }

    static BlockDataSuccessResult of(Supplier<ShuffleBlockInputStream> blockDataStream) {
      return builder().blockDataStream(blockDataStream).build();
    }

    static ImmutableBlockDataSuccessResult.Builder builder() {
      return ImmutableBlockDataSuccessResult.builder();
    }
  }

  @Value.Immutable
  @ImmutablesStyle
  abstract static class BlockDataErrorResult implements BlockDataResult {
    abstract Throwable error();

    abstract ShuffleBlockId shuffleBlockId();

    abstract BlockManagerId blockManagerId();

    @Override
    public ShuffleBlockId getShuffleBlockId() {
      return shuffleBlockId();
    }

    @Override
    public final Supplier<ShuffleBlockInputStream> getResult() {
      // The below actually throws an error - but since FetchFailedException is a checked
      // exception, run Scala code to throw this exception and bypass the need to declare
      // throws or catch and wrap with RuntimeException. We have to throw exactly a
      // FetchFailedException and cannot wrap that exception in anything else.
      return FetchFailedExceptionThrower.throwFetchFailedException(
          shuffleBlockId().shuffleId(),
          shuffleBlockId().mapId(),
          shuffleBlockId().reduceId(),
          blockManagerId(),
          "Exception thrown when fetching data from remote storage.",
          error());
    }

    static ImmutableBlockDataErrorResult.Builder builder() {
      return ImmutableBlockDataErrorResult.builder();
    }
  }

  private static ShuffleBlockId toShuffleBlockId(BlockId blockId) {
    if (blockId instanceof ShuffleBlockId) {
      return (ShuffleBlockId) blockId;
    }
    throw new SafeRuntimeException("Expected block id to be of instance ShuffleBLockAttemptId");
  }
}
