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

package org.apache.spark.palantir.shuffle.async.client.basic;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

/**
 * Module to manage remote shuffle files, allowing for keying shuffle files by identifiers without
 * the need to know the specific path to the file on the backing file system, nor how to
 * specifically open streams to read or write to these files.
 * <p>
 * This encodes the notion of shuffle index files and shuffle data files.
 */
public interface RemoteShuffleFileSystem {

  void deleteApplicationShuffleData() throws IOException;

  SeekableInput getRemoteSeekableIndexFile(int shuffleId, int mapId, long attemptId)
      throws IOException;

  SeekableInput getRemoteSeekableDataFile(int shuffleId, int mapId, long attemptId)
      throws IOException;

  InputStream openRemoteIndexFile(int shuffleId, int mapId, long attemptId) throws IOException;

  void backupIndexFile(int shuffleId, int mapId, long attemptId, File localIndexFile)
      throws IOException;

  void backupDataFile(int shuffleId, int mapId, long attemptId, File localDataFile)
      throws IOException;
}
