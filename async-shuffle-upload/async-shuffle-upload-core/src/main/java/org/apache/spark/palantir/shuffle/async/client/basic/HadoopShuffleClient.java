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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIoException;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import org.apache.hadoop.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.palantir.shuffle.async.AsyncShuffleDataIoSparkConfigs;
import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.client.ShuffleClient;
import org.apache.spark.palantir.shuffle.async.io.PartitionDecoder;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.metrics.BasicShuffleClientMetrics;
import org.apache.spark.palantir.shuffle.async.util.AbortableOutputStream;
import org.apache.spark.palantir.shuffle.async.util.PartitionOffsets;
import org.apache.spark.palantir.shuffle.async.util.Suppliers;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

/**
 * The main driver for dealing with shuffle files that are backed up to the Hadoop file system.
 * <p>
 * This implements the
 * {@link org.apache.spark.palantir.shuffle.async.client.ShuffleStorageStrategy#BASIC} storage
 * strategy, and is the primary storage strategy that is recommended for use.
 * >p>
 * Uploading submits an asynchronous task to upload the index file and the data files.
 * <p>
 * Downloading involves a number of optimizations, primarily around how data blocks are stored to
 * be served to the Spark reducer task. In particular, if the block is small enough to fit in
 * memory, as defined by the configuration threshold given by
 * {@link AsyncShuffleDataIoSparkConfigs#DOWNLOAD_SHUFFLE_BLOCKS_IN_MEMORY_MAX_SIZE()}, then the
 * block is downloaded as an in-memory byte array. Otherwise, the block is saved to local disk.
 * <p>
 * Fetching is done eagerly to allow multiple blocks to be downloaded simultaneously. Returning
 * an InputStream doesn't eagerly download the shuffle block, so reduce tasks that only operate on
 * one data block input stream at a time are only downloading one block at a time. In reality, we
 * prefer a single reduce task to be able to simultaneously download multiple blocks. This behavior
 * is inspired by Spark's bulit-in implementation of shuffle block fetchinig - see
 * {@link org.apache.spark.storage.ShuffleBlockFetcherIterator}.
 */
public final class HadoopShuffleClient implements ShuffleClient {

  private static final InputStream EMPTY_INPUT_STREAM = new ByteArrayInputStream(new byte[0]);
  private static final Supplier<InputStream> EMPTY_INPUT_STREAM_SUPPLIER =
      Suppliers.ofInstance(EMPTY_INPUT_STREAM);

  private static final Logger LOGGER = LoggerFactory.getLogger(HadoopShuffleClient.class);

  private final ListeningExecutorService downloadExecService;
  private final ListeningExecutorService uploadExecService;
  private final RemoteShuffleFileSystem remoteShuffleFs;
  private final LocalDownloadShuffleFileSystem localDownloadShuffleFs;
  private final PartitionOffsetsFetcher partitionOffsetsFetcher;
  private final String appId;
  private final ShuffleDriverEndpointRef shuffleDriverEndpointRef;
  private final Clock clock;
  private final int shuffleDownloadBufferSize;
  private final int localFileBufferSize;
  private final long maxShuffleBytesInMemory;
  private final BasicShuffleClientMetrics events;
  private final AtomicLong pendingOrRunningUploadsCounter = new AtomicLong(0L);

  public HadoopShuffleClient(
      String appId,
      ListeningExecutorService uploadExecService,
      ListeningExecutorService downloadExecService,
      RemoteShuffleFileSystem remoteShuffleFs,
      LocalDownloadShuffleFileSystem localDownloadShuffleFs,
      PartitionOffsetsFetcher partitionOffsetsFetcher,
      BasicShuffleClientMetrics events,
      ShuffleDriverEndpointRef shuffleDriverEndpointRef,
      Clock clock,
      long maxShuffleBytesInMemory,
      int shuffleDownloadBufferSize,
      int localFileBufferSize) {
    this.appId = appId;
    this.uploadExecService = uploadExecService;
    this.downloadExecService = downloadExecService;
    this.remoteShuffleFs = remoteShuffleFs;
    this.localDownloadShuffleFs = localDownloadShuffleFs;
    this.partitionOffsetsFetcher = partitionOffsetsFetcher;
    this.shuffleDriverEndpointRef = shuffleDriverEndpointRef;
    this.clock = clock;
    this.events = events;
    this.maxShuffleBytesInMemory = maxShuffleBytesInMemory;
    this.shuffleDownloadBufferSize = shuffleDownloadBufferSize;
    this.localFileBufferSize = localFileBufferSize;
  }

  @Override
  public void asyncWriteIndexFileAndClose(
      java.nio.file.Path indexFile, int shuffleId, int mapId, long attemptId) {
    doSubmitUpload(
        Optional.empty(),
        indexFile,
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
    doSubmitUpload(
        Optional.of(dataFile),
        indexFile,
        shuffleId,
        mapId,
        attemptId);
  }

  private void doSubmitUpload(
      Optional<java.nio.file.Path> dataFile,
      java.nio.file.Path indexFile,
      int shuffleId,
      int mapId,
      long attemptId) {
    long timeSubmitted = clock.millis();
    events.markUploadRequested(
        shuffleId,
        mapId,
        attemptId,
        pendingOrRunningUploadsCounter.incrementAndGet());
    ListenableFuture<Void> future = uploadExecService.submit(() -> {
      doWriteFilesAndClose(
          dataFile,
          indexFile,
          shuffleId,
          mapId,
          attemptId,
          timeSubmitted);
      return null;
    });
    Futures.addCallback(future, new FutureCallback<Void>() {
      @Override
      public void onSuccess(Void _result) {
        LOGGER.info("Successfully uploaded shuffle files",
            SafeArg.of("shuffleId", shuffleId),
            SafeArg.of("mapId", mapId),
            SafeArg.of("attemptId", attemptId));
      }

      @Override
      public void onFailure(Throwable throwable) {
        LOGGER.info("Failed to upload shuffle files",
            SafeArg.of("shuffleId", shuffleId),
            SafeArg.of("mapId", mapId),
            SafeArg.of("attemptId", attemptId),
            throwable);
      }
    }, uploadExecService);
    events.markUploadRequestSubmitted(
        shuffleId,
        mapId,
        attemptId,
        clock.millis() - timeSubmitted);
  }

  private void doWriteFilesAndClose(
      Optional<java.nio.file.Path> dataFile,
      java.nio.file.Path indexFile,
      int shuffleId,
      int mapId,
      long attemptId,
      long timeSubmitted) {
    try {
      long startTime = clock.millis();
      try {
        if (!shuffleDriverEndpointRef.isShuffleRegistered(shuffleId)) {
          LOGGER.info(
              "Shuffle is no longer active; skipping file upload",
              SafeArg.of("appId", appId),
              SafeArg.of("shuffleId", shuffleId),
              SafeArg.of("mapId", mapId),
              SafeArg.of("attemptId", attemptId));
          return;
        }
      } catch (Exception e) {
        LOGGER.error(
            "Exception encountered while checking for existence of shuffle.",
            SafeArg.of("appId", appId),
            SafeArg.of("shuffleId", shuffleId),
            SafeArg.of("mapId", mapId),
            SafeArg.of("attemptId", attemptId),
            e);
        throw new RuntimeException(e);
      }

      events.markUploadStarted(shuffleId, mapId, attemptId);
      dataFile.ifPresent(
          input -> {
            try {
              remoteShuffleFs.backupDataFile(shuffleId, mapId, attemptId, input.toFile());
            } catch (IOException e) {
              throw new SafeRuntimeException(
                  new SafeIoException("Failed to back up shuffle data file.",
                      e,
                      SafeArg.of("shuffleId", shuffleId),
                      SafeArg.of("mapId", mapId),
                      SafeArg.of("attemptId", attemptId)));
            }
          });

      try {
        remoteShuffleFs.backupIndexFile(shuffleId, mapId, attemptId, indexFile.toFile());
      } catch (IOException e) {
        throw new SafeRuntimeException(
            new SafeIoException("Failed to back up shuffle index file.",
                e,
                SafeArg.of("shuffleId", shuffleId),
                SafeArg.of("mapId", mapId),
                SafeArg.of("attemptId", attemptId)));
      }
      long totalSize = indexFile.toFile().length() +
          dataFile.map(input -> indexFile.toFile().length() +
          input.toFile().length()).orElse(0L);
      long now = clock.millis();

      shuffleDriverEndpointRef.registerUnmergedMapOutput(
          new MapOutputId(shuffleId, mapId, attemptId));
      events.markUploadCompleted(
          shuffleId,
          mapId,
          attemptId,
          now - startTime,
          totalSize,
          now - timeSubmitted,
          pendingOrRunningUploadsCounter.decrementAndGet());
    } catch (Exception e) {
      events.markUploadFailed(
          shuffleId, mapId, attemptId, pendingOrRunningUploadsCounter.decrementAndGet());
      throw e;
    }
  }

  @Override
  public void removeApplicationData() {
    try {
      remoteShuffleFs.deleteApplicationShuffleData();
    } catch (IOException e) {
      LOGGER.error("Encountered error deleting application data",
          SafeArg.of("appId", appId),
          e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public ListenableFuture<Supplier<InputStream>> getBlockData(
      int shuffleId,
      int mapId,
      int reduceId,
      long attemptId) {
    return downloadExecService.submit(() -> {
      long startTime = clock.millis();
      events.markDownloadStarted(shuffleId, mapId, reduceId, attemptId);
      PartitionOffsets offsets = partitionOffsetsFetcher.fetchPartitionOffsets(
          shuffleId, mapId, attemptId, reduceId);
      Supplier<InputStream> resolvedBlockDataInput;
      if (offsets.length() == 0) {
        resolvedBlockDataInput = EMPTY_INPUT_STREAM_SUPPLIER;
      } else {
        SeekableInput dataInput;
        try {
          dataInput = remoteShuffleFs.getRemoteSeekableDataFile(shuffleId, mapId, attemptId);
        } catch (IOException e) {
          throw new SafeRuntimeException(
              new SafeIoException(
                  "Failed to fetch data file from remote storage.",
                  e,
                  SafeArg.of("shuffleId", shuffleId),
                  SafeArg.of("mapId", mapId),
                  SafeArg.of("reduceId", reduceId),
                  SafeArg.of("attemptId", attemptId)));
        }
        if (offsets.length() < maxShuffleBytesInMemory) {
          byte[] partitionBytes;
          try (InputStream partitionInputStream =
                   PartitionDecoder.decodePartitionData(dataInput, offsets)) {
            partitionBytes = org.apache.commons.io.IOUtils.toByteArray(
                partitionInputStream, offsets.length());
          } catch (IOException e) {
            throw new SafeRuntimeException(
                new SafeIoException(
                    "Failed to decode partition from remote storage.",
                    e,
                    SafeArg.of("shuffleId", shuffleId),
                    SafeArg.of("mapId", mapId),
                    SafeArg.of("reduceId", reduceId),
                    SafeArg.of("attemptId", attemptId)));
          }
          ByteArrayInputStream partitionBytesInput = new ByteArrayInputStream(partitionBytes);
          resolvedBlockDataInput = Suppliers.ofInstance(partitionBytesInput);
        } else {
          AbortableOutputStream resolvedTempFileOutput = null;
          try (InputStream blockDataInput =
                   PartitionDecoder.decodePartitionData(dataInput, offsets);
               AbortableOutputStream tempFileOutput =
                   localDownloadShuffleFs.createLocalDataBlockFile(
                       shuffleId, mapId, reduceId, attemptId)) {
            resolvedTempFileOutput = tempFileOutput;
            IOUtils.copyBytes(blockDataInput, tempFileOutput, shuffleDownloadBufferSize);
          } catch (Exception e) {
            if (resolvedTempFileOutput != null) {
              resolvedTempFileOutput.abort(e);
            }
            throw new SafeRuntimeException(
                "Failed to copy block data to local disk.",
                e,
                SafeArg.of("shuffleId", shuffleId),
                SafeArg.of("mapId", mapId),
                SafeArg.of("reduceId", reduceId),
                SafeArg.of("attemptId", attemptId));
          }
          Preconditions.checkState(
              localDownloadShuffleFs.doesLocalDataBlockFileExist(
                  shuffleId, mapId, reduceId, attemptId),
              "Failed to download shuffle file from remote storage.",
              SafeArg.of("shuffleId", shuffleId),
              SafeArg.of("mapId", mapId),
              SafeArg.of("attemptId", attemptId),
              SafeArg.of("reduceId", reduceId));
          resolvedBlockDataInput = Suppliers.memoize(() -> {
            Preconditions.checkState(
                localDownloadShuffleFs.doesLocalDataBlockFileExist(
                    shuffleId, mapId, reduceId, attemptId),
                "Shuffle file was removed between the time it was downloaded and the" +
                    " time we are reading it.",
                SafeArg.of("shuffleId", shuffleId),
                SafeArg.of("mapId", mapId),
                SafeArg.of("attemptId", attemptId),
                SafeArg.of("reduceId", reduceId));
            try {
              return new BufferedInputStream(
                  localDownloadShuffleFs.getLocalSeekableDataBlockFile(
                      shuffleId, mapId, reduceId, attemptId).open(),
                  localFileBufferSize);
            } catch (IOException e) {
              throw new SafeRuntimeException(
                  new SafeIoException(
                      "Failed to open local shuffle data block file.",
                      e,
                      SafeArg.of("shuffleId", shuffleId),
                      SafeArg.of("mapId", mapId),
                      SafeArg.of("attemptId", attemptId),
                      SafeArg.of("reduceId", reduceId)));
            }
          });
        }
      }
      events.markDownloadCompleted(
          shuffleId, mapId, reduceId, attemptId, clock.millis() - startTime);
      return resolvedBlockDataInput;
    });
  }

  @Override
  public void deleteDownloadedBlockData(int shuffleId, int mapId, int reduceId, long attemptId) {
    localDownloadShuffleFs.deleteDownloadedDataBlock(shuffleId, mapId, reduceId, attemptId);
  }
}
