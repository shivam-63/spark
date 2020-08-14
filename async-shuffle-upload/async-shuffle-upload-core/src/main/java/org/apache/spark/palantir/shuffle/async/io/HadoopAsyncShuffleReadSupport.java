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

package org.apache.spark.palantir.shuffle.async.io;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.spark.TaskContext;
import org.apache.spark.io.CompressionCodec;
import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.client.ShuffleClient;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageState;
import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageStateVisitor;
import org.apache.spark.palantir.shuffle.async.metrics.HadoopFetcherIteratorMetrics;
import org.apache.spark.palantir.shuffle.async.reader.DefaultHadoopFetcherIteratorFactory;
import org.apache.spark.palantir.shuffle.async.reader.ExecutorThenHadoopFetcherIterator;
import org.apache.spark.serializer.SerializerManager;
import org.apache.spark.shuffle.api.ShuffleBlockInfo;
import org.apache.spark.shuffle.api.ShuffleBlockInputStream;
import org.apache.spark.shuffle.api.ShuffleExecutorComponents;
import org.apache.spark.storage.BlockManagerId;
import org.apache.spark.util.TaskCompletionListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Submodule that is responsible for retrieving block input streams for reduce tasks.
 * <p>
 * Primarily separated out so that it doesn't have to bloat the contents of
 * {@link HadoopAsyncShuffleExecutorComponents}.
 * <p>
 * When reading a group of blocks, we bucket the blocks into those we can fetch from other
 * executors, and those that must be read from remote storage. The bucket of blocks that can be
 * fetched from other executors are passed to the delegate
 * {@link org.apache.spark.shuffle.sort.io.LocalDiskShuffleExecutorComponents}. The result is a
 * {@link ExecutorThenHadoopFetcherIterator} that can read blocks from both buckets.
 * <p>
 * If {@link #preferDownloadFromHadoop} is set, blocks that could be fetched from either remote
 * storage or from executors are preferred to be fetched from the remote store. Otherwise, we
 * prefer downloading blocks from executors whenever possible.
 */
public final class HadoopAsyncShuffleReadSupport {

  private static final Logger LOG = LoggerFactory.getLogger(HadoopAsyncShuffleReadSupport.class);

  private final ShuffleExecutorComponents delegate;
  private final ShuffleClient client;
  private final SerializerManager serializerManager;
  private final CompressionCodec compressionCodec;
  private final boolean shouldCompressShuffle;
  private final HadoopFetcherIteratorMetrics metrics;
  private final Supplier<Optional<TaskContext>> taskContext;
  private final ShuffleDriverEndpointRef driverEndpointRef;
  private final boolean preferDownloadFromHadoop;

  public HadoopAsyncShuffleReadSupport(
      ShuffleExecutorComponents delegate,
      ShuffleClient client,
      SerializerManager serializerManager,
      CompressionCodec compressionCodec,
      boolean shouldCompressShuffle,
      HadoopFetcherIteratorMetrics metrics,
      Supplier<Optional<TaskContext>> taskContext,
      ShuffleDriverEndpointRef driverEndpointRef,
      boolean preferDownloadFromHadoop) {
    this.delegate = delegate;
    this.client = client;
    this.serializerManager = serializerManager;
    this.compressionCodec = compressionCodec;
    this.shouldCompressShuffle = shouldCompressShuffle;
    this.metrics = metrics;
    this.taskContext = taskContext;
    this.driverEndpointRef = driverEndpointRef;
    this.preferDownloadFromHadoop = preferDownloadFromHadoop;
  }

  public Iterable<ShuffleBlockInputStream> getPartitionReaders(
      Iterable<ShuffleBlockInfo> blockMetadata) throws IOException {
    LOG.debug("Creating hadoop shuffle partition reader");
    Iterator<ShuffleBlockInfo> blockInfoIterator = blockMetadata.iterator();
    if (!blockInfoIterator.hasNext()) {
      return ImmutableList.of();
    }
    final Set<ShuffleBlockInfo> shuffleBlocksFromExecutors = new HashSet<>();
    final Set<ShuffleBlockInfo> shuffleBlocksFromRemote = new HashSet<>();
    int shuffleId = blockInfoIterator.next().getShuffleId();
    Map<MapOutputId, ShuffleStorageState> registeredMapOutputs = driverEndpointRef
        .getShuffleStorageStates(shuffleId);
    blockMetadata.forEach(blockInfo -> {
      ShuffleStorageState blockStorageState = registeredMapOutputs.get(
          new MapOutputId(
              blockInfo.getShuffleId(),
              blockInfo.getMapId(),
              blockInfo.getMapTaskAttemptId()));
      blockStorageState.visit(new ShuffleStorageStateVisitor<Set<ShuffleBlockInfo>>() {

        @Override
        public Set<ShuffleBlockInfo> unregistered() {
          return shuffleBlocksFromExecutors;
        }

        @Override
        public Set<ShuffleBlockInfo> onExecutorOnly(BlockManagerId _executorLocation) {
          return shuffleBlocksFromExecutors;
        }

        @Override
        public Set<ShuffleBlockInfo> onExecutorAndRemote(
            BlockManagerId _executorLocation, Optional<Long> _mergeId) {
          if (preferDownloadFromHadoop) {
            return shuffleBlocksFromRemote;
          } else {
            return shuffleBlocksFromExecutors;
          }
        }

        @Override
        public Set<ShuffleBlockInfo> onRemoteOnly(Optional<Long> _mergeId) {
          return shuffleBlocksFromRemote;
        }
      }).add(blockInfo);
    });

    Iterable<ShuffleBlockInputStream> inputStreams = delegate.getPartitionReaders(
        shuffleBlocksFromExecutors);
    return () -> {
      ExecutorThenHadoopFetcherIterator iterator = new ExecutorThenHadoopFetcherIterator(
          shuffleId,
          inputStreams.iterator(),
          shuffleBlocksFromExecutors,
          shouldCompressShuffle,
          serializerManager,
          compressionCodec,
          shuffleBlocksFromRemote,
          new DefaultHadoopFetcherIteratorFactory(
              client,
              metrics),
          driverEndpointRef);
      taskContext.get().ifPresent(context ->
          context.addTaskCompletionListener(
              new ExecutorThenHadoopShuffleCompletionListener(iterator)));
      return iterator;
    };
  }

  private static final class ExecutorThenHadoopShuffleCompletionListener
      implements TaskCompletionListener {

    private final ExecutorThenHadoopFetcherIterator executorThenHadoopFetcherIterator;

    ExecutorThenHadoopShuffleCompletionListener(
        ExecutorThenHadoopFetcherIterator executorThenHadoopFetcherIterator) {
      this.executorThenHadoopFetcherIterator = executorThenHadoopFetcherIterator;
    }

    @Override
    @SuppressWarnings("StrictUnusedVariable")
    public void onTaskCompletion(TaskContext context) {
      this.executorThenHadoopFetcherIterator.cleanup();
    }
  }
}
