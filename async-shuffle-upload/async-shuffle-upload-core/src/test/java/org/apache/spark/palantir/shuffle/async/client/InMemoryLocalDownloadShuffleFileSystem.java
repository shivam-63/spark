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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.spark.palantir.shuffle.async.client.basic.LocalDownloadShuffleFileSystem;
import org.apache.spark.palantir.shuffle.async.io.SeekableByteArrayInput;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.util.AbortableOutputStream;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

public final class InMemoryLocalDownloadShuffleFileSystem
    implements LocalDownloadShuffleFileSystem {

  private final Map<MapOutputId, Map<Integer, InMemoryFile>> dataBlocks = Maps.newConcurrentMap();
  private final Map<MapOutputId, InMemoryFile> indexFiles = Maps.newConcurrentMap();

  @Override
  public SeekableInput getLocalSeekableDataBlockFile(
      int shuffleId, int mapId, int reduceId, long attemptId) {
    return new SeekableByteArrayInput(
        dataBlocks.get(new MapOutputId(shuffleId, mapId, attemptId))
            .get(reduceId)
            .read());
  }

  @Override
  public byte[] readAllIndexFileBytes(int shuffleId, int mapId, long attemptId) {
    return indexFiles.get(new MapOutputId(shuffleId, mapId, attemptId)).read();
  }

  @Override
  public SeekableInput getLocalSeekableIndexFile(int shuffleId, int mapId, long attemptId) {
    return new SeekableByteArrayInput(readAllIndexFileBytes(shuffleId, mapId, attemptId));
  }

  @Override
  public boolean doesLocalIndexFileExist(int shuffleId, int mapId, long attemptId) {
    return indexFiles.containsKey(new MapOutputId(shuffleId, mapId, attemptId));
  }

  @Override
  public boolean doesLocalDataBlockFileExist(
      int shuffleId, int mapId, int reduceId, long attemptId) {
    return dataBlocks.getOrDefault(
        new MapOutputId(shuffleId, mapId, attemptId),
        ImmutableMap.of())
        .containsKey(reduceId);
  }

  @Override
  public void deleteDownloadedIndexFile(int shuffleId, int mapId, long attemptId) {
    indexFiles.remove(new MapOutputId(shuffleId, mapId, attemptId));
  }

  @Override
  public void deleteDownloadedIndexFilesForShuffleId(int shuffleId) {
    Iterator<Map.Entry<MapOutputId, InMemoryFile>> indexFilesIt = indexFiles.entrySet().iterator();
    while (indexFilesIt.hasNext()) {
      if (indexFilesIt.next().getKey().shuffleId() == shuffleId) {
        indexFilesIt.remove();
      }
    }
  }

  @Override
  public void deleteDownloadedDataBlock(
      int shuffleId, int mapId, int reduceId, long attemptId) {
    dataBlocks.getOrDefault(
        new MapOutputId(shuffleId, mapId, attemptId), new HashMap<>())
        .remove(reduceId);
  }

  @Override
  public AbortableOutputStream createLocalIndexFile(int shuffleId, int mapId, long attemptId) {
    ByteArrayOutputStream indexFileBytesOut = new ByteArrayOutputStream();
    return new AbortableOutputStream(indexFileBytesOut) {
      @Override
      public void abort(Exception _cause) {

      }

      @Override
      public void close() throws IOException {
        super.close();
        indexFiles.put(
            new MapOutputId(shuffleId, mapId, attemptId),
            InMemoryFile.of(indexFileBytesOut.toByteArray()));
      }
    };
  }

  @Override
  public AbortableOutputStream createLocalDataBlockFile(
      int shuffleId, int mapId, int reduceId, long attemptId) {
    ByteArrayOutputStream dataBlockBytesOut = new ByteArrayOutputStream();
    return new AbortableOutputStream(dataBlockBytesOut) {
      @Override
      public void abort(Exception _cause) {

      }

      @Override
      public void close() throws IOException {
        super.close();
        dataBlocks.computeIfAbsent(
            new MapOutputId(shuffleId, mapId, attemptId), _key -> new HashMap<>())
            .put(reduceId, InMemoryFile.of(dataBlockBytesOut.toByteArray()));
      }
    };
  }

  public Map<MapOutputId, Map<Integer, Integer>> dataBlockReadCounts() {
    return Maps.transformValues(
        ImmutableMap.copyOf(dataBlocks),
        blocks -> Maps.transformValues(
            ImmutableMap.copyOf(blocks),
            InMemoryFile::getReadCount));
  }

  public Map<MapOutputId, Integer> indexFileReadCounts() {
    return Maps.transformValues(
        ImmutableMap.copyOf(indexFiles),
        InMemoryFile::getReadCount);
  }
}
