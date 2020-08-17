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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;

import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.client.merging.MergingShuffleFiles;
import org.apache.spark.palantir.shuffle.async.client.merging.MergingShuffleUploadCoordinator;
import org.apache.spark.palantir.shuffle.async.client.merging.ShuffleMapInput;
import org.apache.spark.palantir.shuffle.async.io.PartitionDecoder;
import org.apache.spark.palantir.shuffle.async.merger.FileMerger;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageState;
import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageStateVisitor;
import org.apache.spark.palantir.shuffle.async.metrics.MergingShuffleClientMetrics;
import org.apache.spark.palantir.shuffle.async.util.EmptySizedInput;
import org.apache.spark.palantir.shuffle.async.util.FileSizedInput;
import org.apache.spark.palantir.shuffle.async.util.SizedInput;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;
import org.apache.spark.storage.BlockManagerId;

/**
 * Experimental implementation of {@link ShuffleClient} that concatenates shuffle data and index
 * files before writing them to remote storage.
 * <p>
 * This implements the {@link ShuffleStorageStrategy#MERGING} storage strategy for backing up
 * shuffle files. This was originally built under the assumption that reading and writing small
 * files may be prohibitively expensive. This fact remains to be seen, but this implementation has
 * been largely untested and also should be updated to match optimizations present in
 * {@link org.apache.spark.palantir.shuffle.async.client.basic.HadoopShuffleClient}.
 * <p>
 * When uploading files, the data is not sent to remote storage automatically. A periodic task
 * batches together data and index files, and uploads groups of them as concatenated files.
 * Downloading blocks requires the merged files to be downloaded and split on local disk first
 * before they can be served to the reducer tasks.
 */
public final class MergingHadoopShuffleClient implements ShuffleClient {

  private final String appId;
  private final ListeningExecutorService downloadExecService;
  private final ListeningExecutorService localReadExecService;
  private final MergingShuffleClientMetrics events;
  private final ShuffleDriverEndpointRef shuffleDriverEndpointRef;
  private final MergingShuffleUploadCoordinator shuffleUploadCoordinator;
  private final MergingShuffleFiles shuffleFiles;
  private final Map<Long, ListenableFuture<Void>> downloadDataTasks;
  private final Map<Long, ListenableFuture<Void>> downloadIndicesTasks;
  private final Clock clock;
  private final int localFileBufferSize;

  public MergingHadoopShuffleClient(
      String appId,
      ListeningExecutorService downloadExecService,
      ListeningExecutorService localReadExecService,
      MergingShuffleClientMetrics events,
      ShuffleDriverEndpointRef shuffleDriverEndpointRef,
      MergingShuffleUploadCoordinator shuffleUploadCoordinator,
      MergingShuffleFiles shuffleFiles,
      Clock clock,
      int localFileBufferSize) {
    this.appId = appId;
    this.downloadExecService = downloadExecService;
    this.localReadExecService = localReadExecService;
    this.events = events;
    this.shuffleDriverEndpointRef = shuffleDriverEndpointRef;
    this.shuffleUploadCoordinator = shuffleUploadCoordinator;
    this.shuffleFiles = shuffleFiles;
    this.downloadDataTasks = new ConcurrentHashMap<>();
    this.downloadIndicesTasks = new ConcurrentHashMap<>();
    this.clock = clock;
    this.localFileBufferSize = localFileBufferSize;
  }

  @Override
  public void asyncWriteIndexFileAndClose(
      java.nio.file.Path indexFile, int shuffleId, int mapId, long attemptId) {
    addShuffleMapInputForUpload(
        new EmptySizedInput(),
        new FileSizedInput(indexFile.toFile(), localFileBufferSize),
        shuffleId,
        mapId,
        attemptId);
  }

  @Override
  public void asyncWriteDataAndIndexFilesAndClose(
      java.nio.file.Path dataFile,
      java.nio.file.Path indexFile,
      int shuffleId,
      int mapId,
      long attemptId) {
    addShuffleMapInputForUpload(
        new FileSizedInput(dataFile.toFile(), localFileBufferSize),
        new FileSizedInput(indexFile.toFile(), localFileBufferSize),
        shuffleId,
        mapId,
        attemptId);
  }

  @Override
  public ListenableFuture<Supplier<InputStream>> getBlockData(
      int shuffleId,
      int mapId,
      int reduceId,
      long attemptId) {
    if (!shuffleFiles.doLocalBackupsExist(shuffleId, mapId, attemptId)) {
      ShuffleStorageState storageState = shuffleDriverEndpointRef.getShuffleStorageState(
          new MapOutputId(shuffleId, mapId, attemptId));
      Optional<Long> mergeId = storageState.visit(new ShuffleStorageStateVisitor<Optional<Long>>() {
        @Override
        public Optional<Long> onExecutorOnly(BlockManagerId executorLocation) {
          throw new SafeIllegalStateException(
              "Shuffle file was not backed up to remote storage. It was only stored on the" +
                  " executor, so if the executor is lost, the block must be recomputed.",
              UnsafeArg.of("executorLocation", executorLocation),
              SafeArg.of("shuffleId", shuffleId),
              SafeArg.of("mapId", mapId),
              SafeArg.of("attemptId", attemptId));
        }

        @Override
        public Optional<Long> onExecutorAndRemote(
            BlockManagerId _executorLocation, Optional<Long> mergeId) {
          return mergeId;
        }

        @Override
        public Optional<Long> onRemoteOnly(Optional<Long> mergeId) {
          return mergeId;
        }

        @Override
        public Optional<Long> unregistered() {
          throw new SafeIllegalStateException(
              "Shuffle file was never registered with the driver, or the shuffle was unregistered.",
              SafeArg.of("shuffleId", shuffleId),
              SafeArg.of("mapId", mapId),
              SafeArg.of("attemptId", attemptId));
        }
      });

      if (!mergeId.isPresent()) {
        throw new SafeIllegalStateException(
            "Shuffle file was not backed up as a merged block.",
            SafeArg.of("shuffleId", shuffleId),
            SafeArg.of("mapId", mapId),
            SafeArg.of("attemptId", attemptId));
      }

      List<ListenableFuture<Void>> downloadFutures = new ArrayList<>(2);
      if (!shuffleFiles.doesLocalBackupDataFileExist(shuffleId, mapId, attemptId)) {
        downloadFutures.add(startDownloadTask(
            mergeId.get(),
            downloadDataTasks,
            () -> {
              downloadMergedData(shuffleId, mapId, attemptId, mergeId.get());
              return null;
            }));
      }

      if (!shuffleFiles.doesLocalBackupIndexFileExist(shuffleId, mapId, attemptId)) {
        downloadFutures.add(startDownloadTask(
            mergeId.get(),
            downloadIndicesTasks,
            () -> {
              downloadMergedIndices(shuffleId, mapId, attemptId, mergeId.get());
              return null;
            }));
      }

      if (!downloadFutures.isEmpty()) {
        return Futures.transform(Futures.allAsList(downloadFutures),
            (Function<List<Void>, Supplier<InputStream>>) downloadResults ->
                () -> getDownloadedBlockDataChecked(shuffleId, mapId, reduceId, attemptId),
            localReadExecService);
      } else {
        return asyncGetDownloadedBlockDataChecked(shuffleId, mapId, reduceId, attemptId);
      }
    } else {
      return asyncGetDownloadedBlockDataChecked(shuffleId, mapId, reduceId, attemptId);
    }
  }

  @Override
  public void deleteDownloadedBlockData(
      int _shuffleId, int _mapId, int _reduceId, long _attemptId) {
    // Merging mode is a no-op since it doesn't save data on local disk.
  }

  private void addShuffleMapInputForUpload(
      SizedInput dataSizedInput,
      SizedInput indexSizedInput,
      int shuffleId,
      int mapId,
      long attemptId) {
    shuffleUploadCoordinator.addShuffleMapInputForUpload(
        new ShuffleMapInput(
            new MapOutputId(shuffleId, mapId, attemptId),
            dataSizedInput,
            indexSizedInput));
  }

  private void downloadMergedData(int shuffleId, int mapId, long attemptId, long mergeId) {
    // Have to double check that something hasn't already done the download
    // TODO(mcheah): #
    if (!shuffleFiles.doesLocalBackupDataFileExist(shuffleId, mapId, attemptId)) {
      events.markDownloadRequested(shuffleId, mapId, attemptId, mergeId, "data");
      long startTimeMillis = clock.millis();
      try (InputStream mergedInput = shuffleFiles.openRemoteMergedDataFile(mergeId);
           DataInputStream mergedDataInput = new DataInputStream(mergedInput)) {
        events.markDownloadStarted(shuffleId, mapId, attemptId, mergeId, "data");
        FileMerger.fetchAndSplitMergedInput(
            mergedDataInput,
            (splitShuffleId, splitMapId, splitAttemptId) -> {
              if (!shuffleFiles.doesLocalBackupDataFileExist(
                  splitShuffleId, splitMapId, splitAttemptId)) {
                return Optional.of(() -> shuffleFiles.createLocalBackupDataFile(
                    splitShuffleId, splitMapId, splitAttemptId));
              } else {
                return Optional.empty();
              }
            });
      } catch (IOException e) {
        events.markDownloadFailed(shuffleId, mapId, attemptId, mergeId, "data");
        throw new RuntimeException(e);
      }
      events.markDownloadCompleted(shuffleId, mapId, attemptId, mergeId, "data",
          clock.millis() - startTimeMillis);
    }
  }

  private void downloadMergedIndices(int shuffleId, int mapId, long attemptId, long mergeId) {
    // Have to double check that something hasn't already done the download
    if (!shuffleFiles.doesLocalBackupIndexFileExist(shuffleId, mapId, attemptId)) {
      events.markDownloadRequested(shuffleId, mapId, attemptId, mergeId, "index");
      long startTimeMillis = clock.millis();
      try (InputStream mergedInput = shuffleFiles.openRemoteMergedIndexFile(mergeId);
           DataInputStream mergedDataInput = new DataInputStream(mergedInput)) {
        events.markDownloadStarted(shuffleId, mapId, attemptId, mergeId, "index");
        FileMerger.fetchAndSplitMergedInput(
            mergedDataInput,
            (splitShuffleId, splitMapId, splitAttemptId) -> {
              if (!shuffleFiles.doesLocalBackupIndexFileExist(
                  splitShuffleId, splitMapId, splitAttemptId)) {
                return Optional.of(() -> shuffleFiles.createLocalBackupIndexFile(
                    splitShuffleId, splitMapId, splitAttemptId));
              } else {
                return Optional.empty();
              }
            });
      } catch (IOException e) {
        events.markDownloadFailed(shuffleId, mapId, attemptId, mergeId, "index");
        throw new RuntimeException(e);
      }
      events.markDownloadCompleted(shuffleId, mapId, attemptId, mergeId, "index",
          clock.millis() - startTimeMillis);
    }
  }

  private ListenableFuture<Void> startDownloadTask(
      long mergeId,
      Map<Long, ListenableFuture<Void>> tasks,
      Callable<Void> taskRunnable) {
    return tasks.computeIfAbsent(
        mergeId,
        resolvedMergeId -> downloadExecService.submit(taskRunnable));
  }

  private ListenableFuture<Supplier<InputStream>> asyncGetDownloadedBlockDataChecked(
      int shuffleId,
      int mapId,
      int reduceId,
      long attemptId) {
    return localReadExecService.submit(
        () -> {
          InputStream downloadedData = getDownloadedBlockDataChecked(
              shuffleId, mapId, reduceId, attemptId);
          return () -> downloadedData;
        });
  }

  private InputStream getDownloadedBlockDataChecked(
      int shuffleId,
      int mapId,
      int reduceId,
      long attemptId) {
    Preconditions.checkState(
        shuffleFiles.doLocalBackupsExist(shuffleId, mapId, attemptId),
        "Shuffle backup files should have been downloaded.",
        SafeArg.of("appId", appId),
        SafeArg.of("shuffleId", shuffleId),
        SafeArg.of("mapId", mapId),
        SafeArg.of("attemptId", attemptId));
    SeekableInput dataInput = shuffleFiles.getLocalBackupDataFile(shuffleId, mapId, attemptId);
    SeekableInput indexInput = shuffleFiles.getLocalBackupIndexFile(shuffleId, mapId, attemptId);
    return PartitionDecoder.decodePartition(dataInput, indexInput, reduceId);
  }

  @Override
  public void removeApplicationData() {

  }
}
