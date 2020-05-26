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

import com.google.common.collect.Maps;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.spark.TaskContext;
import org.apache.spark.io.CompressionCodec;
import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageState;
import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageStateVisitor;
import org.apache.spark.serializer.SerializerManager;
import org.apache.spark.shuffle.FetchFailedException;
import org.apache.spark.shuffle.api.ShuffleBlockInfo;
import org.apache.spark.shuffle.api.ShuffleBlockInputStream;
import org.apache.spark.storage.BlockId;
import org.apache.spark.storage.BlockManagerId;
import org.apache.spark.storage.ShuffleBlockAttemptId;
import org.apache.spark.storage.ShuffleBlockId;
import org.apache.spark.storage.ShuffleDataBlockId;
import org.apache.spark.storage.ShuffleIndexBlockId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Returns input streams for shuffle blocks by reading a group of blocks (possibly empty) from the
 * other executors, and reading another group of blocks (possibly empty) from remote storage.
 * <p>
 * The iterator attempts to fetch as many blocks as possible from the
 * {@link #fetchFromExecutorsIterator}, which is actually a
 * {@link org.apache.spark.storage.ShuffleBlockFetcherIterator} obtained from the default local disk
 * shuffle reader. We stop fetching from the fromo-executors iterator if:
 * <p>
 * 1) There is a fetch failure. In this case, we check if all the remaining blocks can be fetched
 *    from remote storage. If we can, we initialize the {@link HadoopFetcherIterator} with the
 *    remaining blocks that need to be fetched, and provide all remaining blocks from there.
 * 2) The local disk iterator is exhausted. In this case, we also fetch all remaining blocks from
 *    remote storage.
 * <p>
 * If we encounter a {@link FetchFailedException} reading shuffle blocks from executors, we also
 * blacklist the offending executor. This will eventually make it such that blocks from that
 * executor are never attempted to be read from that executor again - they either have to be
 * recomputed, or fetched from remote storage.
 */
public final class ExecutorThenHadoopFetcherIterator implements Iterator<ShuffleBlockInputStream> {

  private static final Logger LOG = LoggerFactory.getLogger(
      ExecutorThenHadoopFetcherIterator.class);

  private final Iterator<ShuffleBlockInputStream> fetchFromExecutorsIterator;
  private final HadoopFetcherIteratorFactory hadoopFetcherIteratorFactory;
  private final Map<ShuffleBlockId, ShuffleBlockInfo> remainingAttemptsByBlock;
  private final boolean shouldCompressShuffle;
  private final SerializerManager serializerManager;
  private final CompressionCodec compressionCodec;
  private final ShuffleDriverEndpointRef driverEndpointRef;
  private final int shuffleId;

  private HadoopFetcherIterator hadoopFetcherIterator = null;

  public ExecutorThenHadoopFetcherIterator(
      int shuffleId,
      Iterator<ShuffleBlockInputStream> fetchFromExecutorsIterator,
      Set<ShuffleBlockInfo> shuffleBlocksFromExecutor,
      boolean shouldCompressShuffle,
      SerializerManager serializerManager,
      CompressionCodec compressionCodec,
      Set<ShuffleBlockInfo> shuffleBlocksFromRemote,
      HadoopFetcherIteratorFactory hadoopFetcherIteratorFactory,
      ShuffleDriverEndpointRef driverEndpointRef) {
    this.shuffleId = shuffleId;
    this.fetchFromExecutorsIterator = fetchFromExecutorsIterator;
    this.shouldCompressShuffle = shouldCompressShuffle;
    this.serializerManager = serializerManager;
    this.compressionCodec = compressionCodec;
    this.remainingAttemptsByBlock =
        Maps.newHashMapWithExpectedSize(
            shuffleBlocksFromExecutor.size() + shuffleBlocksFromRemote.size());
    shuffleBlocksFromExecutor.forEach(block -> remainingAttemptsByBlock.put(
        new ShuffleBlockId(block.getShuffleId(), block.getMapId(), block.getReduceId()),
        block));
    shuffleBlocksFromRemote.forEach(block -> remainingAttemptsByBlock.put(
        new ShuffleBlockId(block.getShuffleId(), block.getMapId(), block.getReduceId()),
        block));
    this.hadoopFetcherIteratorFactory = hadoopFetcherIteratorFactory;
    this.driverEndpointRef = driverEndpointRef;
  }

  @Override
  public boolean hasNext() {
    return !remainingAttemptsByBlock.isEmpty();
  }

  @Override
  public ShuffleBlockInputStream next() {
    ShuffleBlockInputStream resultStream = null;
    while (resultStream == null && hasNext()) {
      if (hadoopFetcherIterator == null && fetchFromExecutorsIterator.hasNext()) {
        try {
          resultStream = fetchFromExecutorsIterator.next();
          BlockId resultBlock = resultStream.getBlockId();
          ShuffleBlockId resolvedBlockId = convertBlockId(resultBlock);
          remainingAttemptsByBlock.remove(resolvedBlockId);
        } catch (Throwable e) {
          if (e instanceof FetchFailedException) {
            LOG.warn(
                "Failed to fetch block the regular way, due to a fetch failed"
                    + " exception. Fetching from the hadoop file system instead.",
                e);
            ShuffleBlockInfo blockInfo =
                remainingAttemptsByBlock.get(((FetchFailedException) e).getShuffleBlockId());
            driverEndpointRef.blacklistExecutor(blockInfo.getShuffleLocation().get());
            if (canRetrieveRemainingBlocksFromHadoop()) {
              unsetFetchFailure();
              hadoopFetcherIterator = hadoopFetcherIteratorFactory
                  .createFetcherIteratorForBlocks(remainingAttemptsByBlock.values());
            } else {
              throw e;
            }
          }
        }
      } else if (hadoopFetcherIterator == null) {
        hadoopFetcherIterator = hadoopFetcherIteratorFactory
            .createFetcherIteratorForBlocks(remainingAttemptsByBlock.values());
        resultStream = fetchFromRemote();
      } else {
        resultStream = fetchFromRemote();
      }
    }
    // TODO(mcheah): #8 this should be FetchFailedException, not IllegalStateException.
    if (resultStream == null) {
      throw new SafeIllegalStateException("Could not fetch shuffle blocks from either the" +
          " distributed store or the mapper executor.");
    }
    return resultStream;
  }

  private void unsetFetchFailure() {
    TaskContext taskContext = TaskContext.get();
    if (taskContext != null) {
      taskContext.setFetchFailed(null);
    }
  }

  private ShuffleBlockInputStream fetchFromRemote() {
    Preconditions.checkNotNull(
        hadoopFetcherIterator, "Hadoop fetcher iterator expected to not be null");
    ShuffleBlockInputStream resultStream = hadoopFetcherIterator.next();
    BlockId blockId = resultStream.getBlockId();
    InputStream resultDeserialized = resultStream;
    if (shouldCompressShuffle) {
      resultDeserialized = compressionCodec.compressedInputStream(
          serializerManager.wrapForEncryption(resultDeserialized));
    } else {
      resultDeserialized = serializerManager.wrapForEncryption(resultDeserialized);
    }
    remainingAttemptsByBlock.remove(convertBlockId(blockId));

    if (resultDeserialized == resultStream) {
      return resultStream;
    } else {
      return new ShuffleBlockInputStream(blockId, resultDeserialized);
    }
  }

  private boolean canRetrieveRemainingBlocksFromHadoop() {
    Map<MapOutputId, ShuffleStorageState> registeredMapOutputs = driverEndpointRef
        .getShuffleStorageStates(shuffleId);
    return remainingAttemptsByBlock.values().stream().allMatch(block -> {
      ShuffleStorageState blockStorageState = registeredMapOutputs.get(
          new MapOutputId(block.getShuffleId(), block.getMapId(), block.getMapTaskAttemptId()));
      if (blockStorageState == null) {
        return false;
      }
      boolean existsOnRemote = blockStorageState.visit(new ShuffleStorageStateVisitor<Boolean>() {
        @Override
        public Boolean onExecutorAndRemote(
            BlockManagerId _executorLocation, Optional<Long> _mergeId) {
          return true;
        }

        @Override
        public Boolean onExecutorOnly(BlockManagerId _executorLocation) {
          return false;
        }

        @Override
        public Boolean unregistered() {
          return false;
        }

        @Override
        public Boolean onRemoteOnly(Optional<Long> _mergeId) {
          return true;
        }
      });
      return existsOnRemote;
    });
  }

  public void cleanup() {
    if (hadoopFetcherIterator != null) {
      hadoopFetcherIterator.cleanup();
    }
  }

  private static ShuffleBlockId convertBlockId(BlockId blockId) {
    int shuffleId;
    int mapId;
    int reduceId;
    if (blockId instanceof ShuffleBlockId) {
      return (ShuffleBlockId) blockId;
    } else if (blockId instanceof ShuffleBlockAttemptId) {
      shuffleId = ((ShuffleBlockAttemptId) blockId).shuffleId();
      mapId = ((ShuffleBlockAttemptId) blockId).mapId();
      reduceId = ((ShuffleBlockAttemptId) blockId).reduceId();
    } else if (blockId instanceof ShuffleDataBlockId) {
      shuffleId = ((ShuffleDataBlockId) blockId).shuffleId();
      mapId = ((ShuffleDataBlockId) blockId).mapId();
      reduceId = ((ShuffleDataBlockId) blockId).reduceId();
    } else if (blockId instanceof ShuffleIndexBlockId) {
      shuffleId = ((ShuffleIndexBlockId) blockId).shuffleId();
      mapId = ((ShuffleIndexBlockId) blockId).mapId();
      reduceId = ((ShuffleIndexBlockId) blockId).reduceId();
    } else {
      throw new SafeIllegalArgumentException(
          "Block id is not valid - must be a shuffle block id.",
          SafeArg.of("blockId", blockId));
    }
    return new ShuffleBlockId(shuffleId, mapId, reduceId);
  }
}
