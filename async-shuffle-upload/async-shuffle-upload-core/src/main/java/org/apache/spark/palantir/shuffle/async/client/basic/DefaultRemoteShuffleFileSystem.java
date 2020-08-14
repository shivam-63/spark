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

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.spark.palantir.shuffle.async.util.streams.SeekableHadoopInput;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

public final class DefaultRemoteShuffleFileSystem implements RemoteShuffleFileSystem {

  private static final String DATA_PREFIX = "data";
  private static final String DATA_FILE_TYPE = "data";
  private static final String INDEX_FILE_TYPE = "index";

  private final FileSystem backupFs;
  private final String baseUri;
  private final String appId;

  public DefaultRemoteShuffleFileSystem(FileSystem backupFs, String baseUri, String appId) {
    this.backupFs = backupFs;
    this.baseUri = baseUri;
    this.appId = appId;
  }

  @Override
  public void backupDataFile(int shuffleId, int mapId, long attemptId, File localDataFile)
      throws IOException {
    backupFs.copyFromLocalFile(
        new Path(localDataFile.toURI()),
        getRemoteDataPath(shuffleId, mapId, attemptId));
  }

  @Override
  public void backupIndexFile(int shuffleId, int mapId, long attemptId, File localIndexFile)
      throws IOException {
    backupFs.copyFromLocalFile(
        new Path(localIndexFile.toURI()),
        getRemoteIndexPath(shuffleId, mapId, attemptId));
  }

  @Override
  public InputStream openRemoteIndexFile(int shuffleId, int mapId, long attemptId)
      throws IOException {
    return getRemoteSeekableIndexFile(shuffleId, mapId, attemptId).open();
  }

  @Override
  public SeekableInput getRemoteSeekableDataFile(int shuffleId, int mapId, long attemptId) {
    return new SeekableHadoopInput(getRemoteDataPath(shuffleId, mapId, attemptId), backupFs);
  }

  @Override
  public SeekableInput getRemoteSeekableIndexFile(int shuffleId, int mapId, long attemptId) {
    return new SeekableHadoopInput(getRemoteIndexPath(shuffleId, mapId, attemptId), backupFs);
  }

  @Override
  public void deleteApplicationShuffleData() throws IOException {
    backupFs.delete(
        new Path(baseUri, String.format("%s/%s", DATA_PREFIX, appId)), true);
  }

  private Path getRemoteDataPath(int shuffleId, int mapId, long attemptId) {
    return getRemotePathForFileType(shuffleId, mapId, attemptId, DATA_FILE_TYPE);
  }

  private Path getRemoteIndexPath(int shuffleId, int mapId, long attemptId) {
    return getRemotePathForFileType(shuffleId, mapId, attemptId, INDEX_FILE_TYPE);
  }

  private Path getRemotePathForFileType(int shuffleId, int mapId, long attemptId, String type) {
    return new Path(
        baseUri,
        String.format("%s/%s/%d/%d/%d.%s", DATA_PREFIX, appId, shuffleId, mapId, attemptId, type));
  }
}
