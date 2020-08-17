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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

import org.apache.spark.palantir.shuffle.async.client.basic.RemoteShuffleFileSystem;
import org.apache.spark.palantir.shuffle.async.io.SeekableByteArrayInput;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

public final class InMemoryRemoteShuffleFileSystem implements RemoteShuffleFileSystem {

  private final Map<MapOutputId, InMemoryFile> dataFiles = Maps.newConcurrentMap();
  private final Map<MapOutputId, InMemoryFile> indexFiles = Maps.newConcurrentMap();

  @Override
  public void deleteApplicationShuffleData() {
    dataFiles.clear();
    indexFiles.clear();
  }

  @Override
  public SeekableInput getRemoteSeekableIndexFile(
      int shuffleId, int mapId, long attemptId) throws IOException {
    MapOutputId id = new MapOutputId(shuffleId, mapId, attemptId);
    if (!indexFiles.containsKey(id)) {
      throw new ShuffleIndexMissingException(shuffleId, mapId, attemptId);
    }
    return new SeekableByteArrayInput(
        indexFiles.get(new MapOutputId(shuffleId, mapId, attemptId)).read());
  }

  @Override
  public SeekableInput getRemoteSeekableDataFile(
      int shuffleId, int mapId, long attemptId) throws IOException {
    MapOutputId id = new MapOutputId(shuffleId, mapId, attemptId);
    if (!dataFiles.containsKey(id)) {
      throw new ShuffleDataMissingException(shuffleId, mapId, attemptId);
    }
    return new SeekableByteArrayInput(dataFiles.get(id).read());
  }

  @Override
  public InputStream openRemoteIndexFile(
      int shuffleId, int mapId, long attemptId) throws IOException {
    MapOutputId id = new MapOutputId(shuffleId, mapId, attemptId);
    if (!indexFiles.containsKey(id)) {
      throw new ShuffleIndexMissingException(shuffleId, mapId, attemptId);
    }
    return new ByteArrayInputStream(indexFiles.get(id).read());
  }

  @Override
  public void backupIndexFile(
      int shuffleId, int mapId, long attemptId, File localIndexFile) throws IOException {
    indexFiles.put(
        new MapOutputId(shuffleId, mapId, attemptId),
        InMemoryFile.of(Files.readAllBytes(localIndexFile.toPath())));
  }

  @Override
  public void backupDataFile(
      int shuffleId, int mapId, long attemptId, File localDataFile) throws IOException {
    dataFiles.put(
        new MapOutputId(shuffleId, mapId, attemptId),
        InMemoryFile.of(Files.readAllBytes(localDataFile.toPath())));
  }

  public void putIndexFile(int shuffleId, int mapId, long attemptId, byte[] indexFileContents) {
    indexFiles.put(
        new MapOutputId(shuffleId, mapId, attemptId),
        InMemoryFile.of(indexFileContents));
  }

  public Map<MapOutputId, Integer> dataFileReadCounts() {
    return Maps.transformValues(ImmutableMap.copyOf(dataFiles), InMemoryFile::getReadCount);
  }

  public Map<MapOutputId, Integer> indexFileReadCounts() {
    return Maps.transformValues(ImmutableMap.copyOf(indexFiles), InMemoryFile::getReadCount);
  }
}
