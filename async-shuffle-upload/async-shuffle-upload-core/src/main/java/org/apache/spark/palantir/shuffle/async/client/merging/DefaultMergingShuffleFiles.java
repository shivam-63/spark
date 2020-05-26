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

package org.apache.spark.palantir.shuffle.async.client.merging;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.spark.palantir.shuffle.async.util.TempFileOutputStream;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableFileInput;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

/**
 * Simpler wrapper around local and remote file systems for the merging shuffle file storage
 * strategy.
 */
public final class DefaultMergingShuffleFiles implements MergingShuffleFiles {

  private static final String MERGED_DATA_PREFIX = "merged-data";

  private final String appId;
  private final int localFileBufferSize;
  private final URI remoteBackupBaseUri;
  private final FileSystem fs;
  private final File downloadedBackupsDir;

  private DefaultMergingShuffleFiles(
      String appId,
      int localFileBufferSize,
      URI remoteBackupBaseUri,
      FileSystem fs,
      File downloadedBackupsDir) {
    this.appId = appId;
    this.localFileBufferSize = localFileBufferSize;
    this.remoteBackupBaseUri = remoteBackupBaseUri;
    this.fs = fs;
    this.downloadedBackupsDir = downloadedBackupsDir;
  }

  public static MergingShuffleFiles mergingShuffleFiles(
      String appId,
      int localFileBufferSize,
      URI remoteBackupBaseUri,
      FileSystem fs) {
    File downloadedBackupsDir;
    try {
      downloadedBackupsDir = Files.createTempDirectory("downloaded-shuffle-backups").toFile();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return new DefaultMergingShuffleFiles(
        appId, localFileBufferSize, remoteBackupBaseUri, fs, downloadedBackupsDir);
  }

  @Override
  public OutputStream createRemoteMergedDataFile(long mergeId) throws IOException {
    return fs.create(mergedDataPath(mergeId));
  }

  @Override
  public OutputStream createRemoteMergedIndexFile(long mergeId) throws IOException {
    return fs.create(mergedIndexPath(mergeId));
  }

  @Override
  public InputStream openRemoteMergedDataFile(long mergeId) throws IOException {
    return fs.open(mergedDataPath(mergeId));
  }

  @Override
  public InputStream openRemoteMergedIndexFile(long mergeId) throws IOException {
    return fs.open(mergedIndexPath(mergeId));
  }

  @Override
  public boolean doesLocalBackupIndexFileExist(int shuffleId, int mapId, long attemptId) {
    return localBackupIndexFile(shuffleId, mapId, attemptId).isFile();
  }

  @Override
  public boolean doesLocalBackupDataFileExist(int shuffleId, int mapId, long attemptId) {
    return localBackupDataFile(shuffleId, mapId, attemptId).isFile();
  }

  @Override
  public OutputStream createLocalBackupDataFile(
      int shuffleId, int mapId, long attemptId) throws IOException {
    return createTempBackupFile(localBackupDataFile(shuffleId, mapId, attemptId));
  }

  @Override
  public OutputStream createLocalBackupIndexFile(
      int shuffleId, int mapId, long attemptId) throws IOException {
    return createTempBackupFile(localBackupIndexFile(shuffleId, mapId, attemptId));
  }

  @Override
  public SeekableInput getLocalBackupDataFile(int shuffleId, int mapId, long attemptId) {
    return new SeekableFileInput(localBackupDataFile(shuffleId, mapId, attemptId));
  }

  @Override
  public SeekableInput getLocalBackupIndexFile(int shuffleId, int mapId, long attemptId) {
    return new SeekableFileInput(localBackupIndexFile(shuffleId, mapId, attemptId));
  }

  private OutputStream createTempBackupFile(File finalFile) throws IOException {
    return new BufferedOutputStream(
        TempFileOutputStream.createTempFile(finalFile),
        localFileBufferSize);
  }

  private Path mergedDataPath(long mergeId) {
    return mergedFilePath("data", mergeId);
  }

  private Path mergedIndexPath(long mergeId) {
    return mergedFilePath("index", mergeId);
  }

  private Path mergedFilePath(String extension, long mergeId) {
    return new Path(new Path(remoteBackupBaseUri),
        String.format(
            "%s/appId=%s/merged-%d.%s",
            MERGED_DATA_PREFIX,
            appId,
            mergeId,
            extension));
  }

  private File localBackupDataFile(int shuffleId, int mapId, long attemptId) {
    return toLocalBackupFile(shuffleId, mapId, attemptId, "data");
  }

  private File localBackupIndexFile(int shuffleId, int mapId, long attemptId) {
    return toLocalBackupFile(shuffleId, mapId, attemptId, "index");
  }

  private File toLocalBackupFile(int shuffleId, int mapId, long attemptId, String extension) {
    java.nio.file.Path dataDir =
        downloadedBackupsDir.toPath()
            .resolve(String.format("shuffle=%d", shuffleId))
            .resolve(String.format("map=%d", mapId))
            .resolve(String.format("attempt=%d", attemptId));
    if (!dataDir.toFile().isDirectory()) {
      try {
        Files.createDirectories(dataDir);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return dataDir.resolve(String.format("shuffle.%s", extension)).toFile();
  }
}
