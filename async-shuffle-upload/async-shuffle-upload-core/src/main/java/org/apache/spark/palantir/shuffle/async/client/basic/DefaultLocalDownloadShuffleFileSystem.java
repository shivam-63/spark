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

import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

import org.apache.spark.palantir.shuffle.async.util.TempFileOutputStream;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableFileInput;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

public final class DefaultLocalDownloadShuffleFileSystem implements LocalDownloadShuffleFileSystem {

  private final File downloadRootDir;

  public DefaultLocalDownloadShuffleFileSystem(File downloadRootDir) {
    this.downloadRootDir = downloadRootDir;
  }

  @Override
  public TempFileOutputStream createLocalDataBlockFile(
      int shuffleId, int mapId, int reduceId, long attemptId) throws IOException {
    File dataBlockFile = getLocalDataBlockFile(shuffleId, mapId, reduceId, attemptId);
    Files.createParentDirs(dataBlockFile);
    return TempFileOutputStream.createTempFile(dataBlockFile);
  }

  @Override
  public TempFileOutputStream createLocalIndexFile(int shuffleId, int mapId, long attemptId)
      throws IOException {
    File indexFile = getLocalIndexFile(shuffleId, mapId, attemptId);
    Files.createParentDirs(indexFile);
    return TempFileOutputStream.createTempFile(indexFile);
  }

  @Override
  public void deleteDownloadedDataBlock(int shuffleId, int mapId, int reduceId, long attemptId) {
    FileUtils.deleteQuietly(getLocalDataBlockFile(shuffleId, mapId, reduceId, attemptId));
  }

  @Override
  public void deleteDownloadedIndexFile(int shuffleId, int mapId, long attemptId) {
    FileUtils.deleteQuietly(getLocalIndexFile(shuffleId, mapId, attemptId));
  }

  @Override
  public void deleteDownloadedIndexFilesForShuffleId(int shuffleId) {
    FileUtils.deleteQuietly(getLocalShuffleIndexDir(shuffleId));
  }

  @Override
  public boolean doesLocalDataBlockFileExist(
      int shuffleId, int mapId, int reduceId, long attemptId) {
    return getLocalDataBlockFile(shuffleId, mapId, reduceId, attemptId).isFile();
  }

  @Override
  public boolean doesLocalIndexFileExist(int shuffleId, int mapId, long attemptId) {
    return getLocalIndexFile(shuffleId, mapId, attemptId).isFile();
  }

  @Override
  public SeekableInput getLocalSeekableIndexFile(int shuffleId, int mapId, long attemptId) {
    return new SeekableFileInput(getLocalIndexFile(shuffleId, mapId, attemptId));
  }

  @Override
  public byte[] readAllIndexFileBytes(int shuffleId, int mapId, long attemptId) throws IOException {
    return java.nio.file.Files.readAllBytes(
        getLocalIndexFile(shuffleId, mapId, attemptId).toPath());
  }

  @Override
  public SeekableInput getLocalSeekableDataBlockFile(
      int shuffleId, int mapId, int reduceId, long attemptId) {
    return new SeekableFileInput(getLocalDataBlockFile(shuffleId, mapId, reduceId, attemptId));
  }

  private File getLocalDataBlockFile(int shuffleId, int mapId, int reduceId, long attemptId) {
    return getLocalShuffleBlocksDir(shuffleId).toPath()
        .resolve(String.format("map=%d", mapId))
        .resolve(String.format("attempt=%d", attemptId))
        .resolve(String.format("reduce=%d", reduceId))
        .toFile();
  }

  private File getLocalIndexFile(int shuffleId, int mapId, long attemptId) {
    return getLocalShuffleIndexDir(shuffleId).toPath()
        .resolve(String.format("map=%d", mapId))
        .resolve(String.format("attempt=%d.index", attemptId))
        .toFile();
  }

  private File getLocalShuffleBlocksDir(int shuffleId) {
    return downloadRootDir.toPath().resolve("downloaded-shuffle-blocks")
        .resolve(String.format("shuffle=%d", shuffleId))
        .toFile();
  }

  private File getLocalShuffleIndexDir(int shuffleId) {
    return downloadRootDir.toPath().resolve("downloaded-index-files")
        .resolve(String.format("shuffle=%d", shuffleId))
        .toFile();
  }
}
