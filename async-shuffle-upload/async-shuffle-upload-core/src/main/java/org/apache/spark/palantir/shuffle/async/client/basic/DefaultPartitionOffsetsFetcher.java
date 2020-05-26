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

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIoException;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.palantir.shuffle.async.AsyncShuffleDataIoSparkConfigs;
import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.io.PartitionDecoder;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.util.AbortableOutputStream;
import org.apache.spark.palantir.shuffle.async.util.PartitionOffsets;
import org.apache.spark.palantir.shuffle.async.util.streams.BufferedSeekableInput;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

/**
 * Default implementation of {@link PartitionOffsetsFetcher} that is backed by index files stored
 * by the {@link HadoopShuffleClient} module.
 * <p>
 * This module may cache index files on local disk, if it is so configured via
 * {@link AsyncShuffleDataIoSparkConfigs#CACHE_INDEX_FILES_LOCALLY()}. An asynchronous task also
 * periodically removes downloaded index files that are no longer needed.
 */
public final class DefaultPartitionOffsetsFetcher implements PartitionOffsetsFetcher {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultPartitionOffsetsFetcher.class);

  private final LocalDownloadShuffleFileSystem localShuffleFs;
  private final RemoteShuffleFileSystem remoteShuffleFs;
  private final int localFileBufferSize;
  private final int shuffleDownloadBufferSize;
  private final boolean cacheIndexFilesLocally;
  private final Map<MapOutputId, Boolean> fetchingIndexFiles;
  private final Set<Integer> fetchedShuffleIds;
  private final ListeningScheduledExecutorService cleanupExecutor;
  private final ShuffleDriverEndpointRef driverEndpointRef;

  public DefaultPartitionOffsetsFetcher(
      LocalDownloadShuffleFileSystem localShuffleFs,
      RemoteShuffleFileSystem remoteShuffleFs,
      ScheduledExecutorService cleanupExecutor,
      ShuffleDriverEndpointRef driverEndpointRef,
      int localFileBufferSize,
      int shuffleDownloadBufferSize,
      boolean cacheIndexFilesLocally) {
    this.localShuffleFs = localShuffleFs;
    this.remoteShuffleFs = remoteShuffleFs;
    this.cleanupExecutor = MoreExecutors.listeningDecorator(cleanupExecutor);
    this.driverEndpointRef = driverEndpointRef;
    this.localFileBufferSize = localFileBufferSize;
    this.shuffleDownloadBufferSize = shuffleDownloadBufferSize;
    this.cacheIndexFilesLocally = cacheIndexFilesLocally;
    this.fetchingIndexFiles = new ConcurrentHashMap<>();
    this.fetchedShuffleIds = ConcurrentHashMap.newKeySet();
  }

  public void startCleaningIndexFiles() {
    cleanupExecutor.scheduleWithFixedDelay(
        this::cleanupUnregisteredIndexFiles, 30, 30, TimeUnit.SECONDS);
  }

  @Override
  public PartitionOffsets fetchPartitionOffsets(
      int shuffleId, int mapId, long mapAttemptId, int reduceId) {
    if (cacheIndexFilesLocally) {
      MapOutputId id = new MapOutputId(shuffleId, mapId, mapAttemptId);
      fetchIndexFileToDisk(id);
      SeekableInput indexFileInput = localShuffleFs.getLocalSeekableIndexFile(
          shuffleId, mapId, mapAttemptId);
      BufferedSeekableInput bufferedIndexFileInput = new BufferedSeekableInput(
          indexFileInput, localFileBufferSize);
      return PartitionDecoder.decodePartitionOffsets(bufferedIndexFileInput, reduceId);
    } else {
      try {
        SeekableInput indexInput = remoteShuffleFs.getRemoteSeekableIndexFile(
            shuffleId, mapId, mapAttemptId);
        BufferedSeekableInput bufferedIndexInput = new BufferedSeekableInput(
            indexInput, shuffleDownloadBufferSize);
        return PartitionDecoder.decodePartitionOffsets(bufferedIndexInput, reduceId);
      } catch (IOException e) {
        throw new SafeRuntimeException(
            new SafeIoException(
                "Failed to decode partition offsets from remote storage.",
                e,
                SafeArg.of("shuffleId", shuffleId),
                SafeArg.of("mapId", mapId),
                SafeArg.of("mapAttemptId", mapAttemptId),
                SafeArg.of("reduceId", reduceId)));
      }
    }
  }

  private void fetchIndexFileToDisk(MapOutputId key) {
    if (localShuffleFs.doesLocalIndexFileExist(key.shuffleId(), key.mapId(), key.mapAttemptId())) {
      return;
    }
    fetchingIndexFiles.computeIfAbsent(key, id -> {
      int shuffleId = key.shuffleId();
      int mapId = key.mapId();
      long mapAttemptId = key.mapAttemptId();
      if (!localShuffleFs.doesLocalIndexFileExist(shuffleId, mapId, mapAttemptId)) {
        AbortableOutputStream resolvedIndexOutput = null;
        try (InputStream remoteIndexInput = remoteShuffleFs.openRemoteIndexFile(
            shuffleId, mapId, mapAttemptId);
             AbortableOutputStream localIndexOutput = localShuffleFs.createLocalIndexFile(
                 shuffleId, mapId, mapAttemptId)) {
          resolvedIndexOutput = localIndexOutput;
          IOUtils.copy(remoteIndexInput, localIndexOutput, shuffleDownloadBufferSize);
        } catch (IOException e) {
          if (resolvedIndexOutput != null) {
            resolvedIndexOutput.abort(e);
          }
          throw new SafeRuntimeException(
              new SafeIoException(
                  "Failed to fetch index file from remote storage to local disk.",
                  e,
                  SafeArg.of("shuffleId", id.shuffleId()),
                  SafeArg.of("mapId", id.mapId()),
                  SafeArg.of("mapAttemptId", id.mapAttemptId())));
        }
      }
      fetchedShuffleIds.add(key.shuffleId());
      return true;
    });
    fetchingIndexFiles.remove(key);
  }

  private void cleanupUnregisteredIndexFiles() {
    try {
      Iterator<Integer> fetchedShufflesIterator = fetchedShuffleIds.iterator();
      while (fetchedShufflesIterator.hasNext()) {
        int shuffleId = fetchedShufflesIterator.next();
        if (!driverEndpointRef.isShuffleRegistered(shuffleId)) {
          fetchedShufflesIterator.remove();
          localShuffleFs.deleteDownloadedIndexFilesForShuffleId(shuffleId);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to clean up unregistered index files.", e);
    }
  }
}
