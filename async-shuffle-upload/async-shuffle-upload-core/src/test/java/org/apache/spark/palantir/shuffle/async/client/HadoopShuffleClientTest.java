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

import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.client.basic.HadoopShuffleClient;
import org.apache.spark.palantir.shuffle.async.client.basic.PartitionOffsetsFetcher;
import org.apache.spark.palantir.shuffle.async.client.basic.RemoteShuffleFileSystem;
import org.apache.spark.palantir.shuffle.async.metrics.slf4j.Slf4jBasicShuffleClientMetrics;

import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

public final class HadoopShuffleClientTest {

  private static final int NUM_PARTITIONS = 7;
  private static final int MAX_PARTITION_SIZE_BYTES = 1000;
  private static final String APP_ID = "my-app";
  private static final int SHUFFLE_ID = 10;
  private static final int MAP_ID = 1;
  private static final int ATTEMPT_ID = 0;

  private static final DeterministicScheduler downloadExecService = new DeterministicScheduler();
  private static final DeterministicScheduler uploadExecService = new DeterministicScheduler();

  private static final Random random = new Random();

  @Mock
  private ShuffleDriverEndpointRef mockDriverEndpointRef;

  private HadoopShuffleClient clientUnderTest;

  private byte[][] data;
  private File dataFile;
  private File indexFile;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Before
  public void before() throws Exception {
    MockitoAnnotations.initMocks(this);
    data = new byte[NUM_PARTITIONS][];
    dataFile = tempDir.newFile();
    indexFile = tempDir.newFile();

    long lengthSoFar = 0;

    try (OutputStream dataInput = new FileOutputStream(dataFile);
         DataOutputStream indexInput = new DataOutputStream(new FileOutputStream(indexFile))) {
      for (int i = 0; i < NUM_PARTITIONS; i++) {
        int size = random.nextInt(MAX_PARTITION_SIZE_BYTES);
        byte[] randomBytes = new byte[size];
        random.nextBytes(randomBytes);
        dataInput.write(randomBytes);
        indexInput.writeLong(lengthSoFar);
        lengthSoFar += size;
        data[i] = randomBytes;
      }
    }
    RemoteShuffleFileSystem remoteShuffleFs = new InMemoryRemoteShuffleFileSystem();
    PartitionOffsetsFetcher partitionOffsetsFetcher = new TestPartitionOffsetsFetcher(
        remoteShuffleFs);
    clientUnderTest = new HadoopShuffleClient(
        APP_ID,
        MoreExecutors.listeningDecorator(uploadExecService),
        MoreExecutors.listeningDecorator(downloadExecService),
        remoteShuffleFs,
        new InMemoryLocalDownloadShuffleFileSystem(),
        partitionOffsetsFetcher,
        new Slf4jBasicShuffleClientMetrics("app-id"),
        mockDriverEndpointRef,
        Clock.systemUTC(),
        5,
        128,
        128
    );
  }

  @Test
  public void testWriteAndRead() throws Exception {
    when(mockDriverEndpointRef.isShuffleRegistered(SHUFFLE_ID)).thenReturn(true);
    clientUnderTest.asyncWriteDataAndIndexFilesAndClose(
        dataFile.toPath(),
        indexFile.toPath(),
        SHUFFLE_ID,
        MAP_ID,
        ATTEMPT_ID);
    uploadExecService.runUntilIdle();
    checkPartitionData(0);
    checkPartitionData(4);
  }

  @Test
  public void testShuffleDoesNotExist() {
    when(mockDriverEndpointRef.isShuffleRegistered(SHUFFLE_ID)).thenReturn(false);
    clientUnderTest.asyncWriteDataAndIndexFilesAndClose(
        dataFile.toPath(),
        indexFile.toPath(),
        SHUFFLE_ID,
        MAP_ID,
        ATTEMPT_ID);
    uploadExecService.runUntilIdle();
    assertThatThrownBy(() -> checkPartitionData(0))
        .hasRootCauseExactlyInstanceOf(ShuffleIndexMissingException.class);
  }

  private void checkPartitionData(int partitionId)
      throws ExecutionException, InterruptedException, IOException {
    ListenableFuture<Supplier<InputStream>> inputStreamFuture =
        clientUnderTest.getBlockData(
            SHUFFLE_ID, MAP_ID, partitionId, ATTEMPT_ID);
    downloadExecService.runUntilIdle();
    InputStream inputStream = inputStreamFuture.get().get();

    byte[] partitionData = data[partitionId];
    byte[] readBytes = ByteStreams.toByteArray(inputStream);
    assertThat(readBytes).containsExactly(partitionData);
  }
}
