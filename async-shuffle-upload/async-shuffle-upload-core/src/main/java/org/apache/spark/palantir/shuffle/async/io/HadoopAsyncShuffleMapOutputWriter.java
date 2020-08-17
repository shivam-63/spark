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

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.client.ShuffleClient;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.shuffle.api.MapOutputWriterCommitMessage;
import org.apache.spark.shuffle.api.ShuffleMapOutputWriter;
import org.apache.spark.shuffle.api.ShufflePartitionWriter;

/**
 * Implementation of {@link ShuffleMapOutputWriter} that delegates to a
 * {@link org.apache.spark.shuffle.sort.io.LocalDiskShuffleMapOutputWriter} to write partitions
 * to local disk, then kicks off an asynchronous backup task to upload the map output data and
 * index files to remote storage.
 */
public final class HadoopAsyncShuffleMapOutputWriter implements ShuffleMapOutputWriter {

  private final ShuffleMapOutputWriter delegate;
  private final ShuffleClient shuffleClient;
  private final ShuffleFileLocator shuffleFileLocator;
  private final Set<ShufflePartitionWriter> shufflePartitionWriters;
  private final ShuffleDriverEndpointRef shuffleDriverEndpointRef;
  private final int shuffleId;
  private final int mapId;
  private final long attemptId;

  public HadoopAsyncShuffleMapOutputWriter(
      ShuffleMapOutputWriter delegate,
      ShuffleClient shuffleClient,
      ShuffleFileLocator shuffleFileLocator,
      ShuffleDriverEndpointRef shuffleDriverEndpointRef, int shuffleId,
      int mapId,
      long attemptId) {
    this.delegate = delegate;
    this.shuffleClient = shuffleClient;
    this.shuffleFileLocator = shuffleFileLocator;
    this.shuffleDriverEndpointRef = shuffleDriverEndpointRef;
    this.shuffleId = shuffleId;
    this.mapId = mapId;
    this.attemptId = attemptId;
    this.shufflePartitionWriters = new HashSet<>();
  }

  @Override
  public ShufflePartitionWriter getPartitionWriter(int partitionId) throws IOException {
    ShufflePartitionWriter writer = delegate.getPartitionWriter(partitionId);
    shufflePartitionWriters.add(writer);
    return writer;
  }

  @Override
  public MapOutputWriterCommitMessage commitAllPartitions() throws IOException {
    MapOutputWriterCommitMessage delegateCommitMessage = delegate.commitAllPartitions();

    File dataFile = shuffleFileLocator.getDataFile(shuffleId, mapId);
    File indexFile = shuffleFileLocator.getIndexFile(shuffleId, mapId);
    shuffleDriverEndpointRef.registerLocallyWrittenMapOutput(new MapOutputId(
        shuffleId, mapId, attemptId));
    if (dataFile.exists()) {
      shuffleClient.asyncWriteDataAndIndexFilesAndClose(
          dataFile.toPath(),
          indexFile.toPath(),
          shuffleId,
          mapId,
          attemptId);
    } else {
      shuffleClient.asyncWriteIndexFileAndClose(
          indexFile.toPath(),
          shuffleId,
          mapId,
          attemptId);
    }

    return delegateCommitMessage;
  }

  @Override
  public void abort(Throwable error) throws IOException {
    delegate.abort(error);
  }
}
