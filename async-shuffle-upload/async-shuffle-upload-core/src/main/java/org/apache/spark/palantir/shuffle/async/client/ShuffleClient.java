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

package org.apache.spark.palantir.shuffle.async.client;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Supplier;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * API for storing shuffle files in remote storage and fetching partition data blocks.
 */
public interface ShuffleClient {

  /**
   * Write only an index file to the backing storage system, assuming there is no data file for
   * this map output task.
   * <p>
   * The backup should be asynchronous, so this method should return immediately after submitting
   * the work to upload the file.
   */
  void asyncWriteIndexFileAndClose(
      Path indexFile,
      int shuffleId,
      int mapId,
      long attemptId);

  /**
   * Back up a map task's data and index files to remote storage.
   * <p>
   * The backup should be asynchronous, so this method should return immediately after submitting
   * the work to upload the file.
   */
  void asyncWriteDataAndIndexFilesAndClose(
      Path dataFile,
      Path indexFile,
      int shuffleId,
      int mapId,
      long attemptId);

  /**
   * Retrieve a handle to an input stream that can be used to read bytes for a given partition.
   * <p>
   * The task to fetch the partition is asynchronous, hence the return value being a future that
   * represents the work being done on a separate thread.
   * <p>
   * The value in the future is a {@link Supplier} since we want to open the resources that back
   * the input stream lazily.
   */
  ListenableFuture<Supplier<InputStream>> getBlockData(
      int shuffleId,
      int mapId,
      int reduceId,
      long attemptId);

  /**
   * Clean up any resources that may have been used to fetch a block retrieved by
   * {@link #getBlockData(int, int, int, long)}.
   * <p>
   * Implementations of {@link #getBlockData(int, int, int, long)} may cache data so that the same
   * block that is fetched twice does not need to be re-fetched from remote storage. This method
   * serves as a handle to clear any resources that may be tied to fetching that block.
   */
  void deleteDownloadedBlockData(int shuffleId, int mapId, int reduceId, long attemptId);

  /**
   * Remove all temporary shuffle files written for this application.
   */
  void removeApplicationData();
}
