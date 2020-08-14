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

package org.apache.spark.shuffle.sort.io;

import com.google.common.annotations.VisibleForTesting;
import org.apache.spark.MapOutputTracker;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkEnv;
import org.apache.spark.serializer.SerializerManager;
import org.apache.spark.shuffle.IndexShuffleBlockResolver;
import org.apache.spark.shuffle.api.*;
import org.apache.spark.shuffle.io.LocalDiskShuffleReadSupport;
import org.apache.spark.storage.BlockManager;
import org.apache.spark.storage.BlockManagerId;

import java.util.Map;
import java.util.Optional;

public class LocalDiskShuffleExecutorComponents implements ShuffleExecutorComponents {

  private final SparkConf sparkConf;
  private LocalDiskShuffleReadSupport shuffleReadSupport;
  private BlockManagerId shuffleServerId;
  private BlockManager blockManager;
  private IndexShuffleBlockResolver blockResolver;

  public LocalDiskShuffleExecutorComponents(SparkConf sparkConf) {
    this.sparkConf = sparkConf;
  }

  @VisibleForTesting
  public LocalDiskShuffleExecutorComponents(
      SparkConf sparkConf,
      BlockManager blockManager,
      MapOutputTracker mapOutputTracker,
      SerializerManager serializerManager,
      IndexShuffleBlockResolver blockResolver,
      BlockManagerId shuffleServerId) {
    this.sparkConf = sparkConf;
    this.blockManager = blockManager;
    this.blockResolver = blockResolver;
    this.shuffleServerId = shuffleServerId;
    this.shuffleReadSupport = new LocalDiskShuffleReadSupport(
      blockManager, mapOutputTracker, serializerManager, sparkConf);
  }

  @Override
  public void initializeExecutor(String appId, String execId, Map<String, String> extraConfigs) {
    blockManager = SparkEnv.get().blockManager();
    if (blockManager == null) {
      throw new IllegalStateException("No blockManager available from the SparkEnv.");
    }
    shuffleServerId = blockManager.shuffleServerId();
    blockResolver = new IndexShuffleBlockResolver(sparkConf, blockManager);
    MapOutputTracker mapOutputTracker = SparkEnv.get().mapOutputTracker();
    SerializerManager serializerManager = SparkEnv.get().serializerManager();
    shuffleReadSupport = new LocalDiskShuffleReadSupport(
      blockManager, mapOutputTracker, serializerManager, sparkConf);
  }

  @Override
  public ShuffleMapOutputWriter createMapOutputWriter(
      int shuffleId,
      int mapId,
      long mapTaskAttemptId,
      int numPartitions) {
    if (blockResolver == null) {
      throw new IllegalStateException(
          "Executor components must be initialized before getting writers.");
    }
    return new LocalDiskShuffleMapOutputWriter(
      shuffleId,
      mapId,
      numPartitions,
      blockResolver,
      shuffleServerId,
      sparkConf);
  }

  @Override
  public Optional<SingleSpillShuffleMapOutputWriter> createSingleFileMapOutputWriter(
      int shuffleId,
      int mapId,
      long mapTaskAttemptId) {
    if (blockResolver == null) {
      throw new IllegalStateException(
          "Executor components must be initialized before getting writers.");
    }
    return Optional.of(new LocalDiskSingleSpillMapOutputWriter(
        shuffleId, mapId, blockResolver, shuffleServerId));
  }

  @Override
  public Iterable<ShuffleBlockInputStream> getPartitionReaders(
      Iterable<ShuffleBlockInfo> blockMetadata) {
    if (blockResolver == null) {
      throw new IllegalStateException(
          "Executor components must be initialized before getting readers.");
    }
    return shuffleReadSupport.getPartitionReaders(blockMetadata);
  }

  @Override
  public boolean shouldWrapPartitionReaderStream() {
    return false;
  }
}
