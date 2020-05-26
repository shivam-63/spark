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

import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeRuntimeException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import org.apache.spark.palantir.shuffle.async.util.AbortableOutputStream;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

/**
 * Module to manage shuffle files that we download from remote storage on the local file system,
 * allowing higher-order entities to manage downloaded data without the need to know specific and
 * consistent file paths where shuffle files are stored.
 * <p>
 * This encodes the notion of shuffle index files and shuffle data block files.
 */
public interface LocalDownloadShuffleFileSystem {

  static LocalDownloadShuffleFileSystem createWithLocalDir(String localDir) {
    File downloadFolder = Paths.get(localDir).resolve("downloaded-shuffle-files").toFile();
    try {
      java.nio.file.Files.createDirectories(downloadFolder.toPath());
    } catch (IOException e) {
      throw new SafeRuntimeException(
          "Failed to create shuffle files download directory.",
          e,
          SafeArg.of("path", downloadFolder.getAbsolutePath()));
    }
    return new DefaultLocalDownloadShuffleFileSystem(downloadFolder);
  }

  SeekableInput getLocalSeekableDataBlockFile(
      int shuffleId, int mapId, int reduceId, long attemptId) throws IOException;

  byte[] readAllIndexFileBytes(int shuffleId, int mapId, long attemptId) throws IOException;

  SeekableInput getLocalSeekableIndexFile(int shuffleId, int mapId, long attemptId);

  boolean doesLocalIndexFileExist(int shuffleId, int mapId, long attemptId);

  boolean doesLocalDataBlockFileExist(int shuffleId, int mapId, int reduceId, long attemptId);

  void deleteDownloadedIndexFile(int shuffleId, int mapId, long attemptId);

  void deleteDownloadedIndexFilesForShuffleId(int shuffleId);

  void deleteDownloadedDataBlock(int shuffleId, int mapId, int reduceId, long attemptId);

  AbortableOutputStream createLocalIndexFile(
      int shuffleId, int mapId, long attemptId) throws IOException;

  AbortableOutputStream createLocalDataBlockFile(
      int shuffleId, int mapId, int reduceId, long attemptId) throws IOException;
}
