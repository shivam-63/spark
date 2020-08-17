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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.client.InMemoryLocalDownloadShuffleFileSystem;
import org.apache.spark.palantir.shuffle.async.client.InMemoryRemoteShuffleFileSystem;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.util.PartitionOffsets;

import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public final class DefaultPartitionOffsetsFetcherTest {

  private static final long[] TEST_OFFSETS_1 = new long[]{0L, 3L, 7L, 20L};
  private static final byte[] TEST_OFFSETS_1_BYTES;

  static {
    ByteArrayOutputStream resolvedBytesOut;
    try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
         DataOutputStream dataOut = new DataOutputStream(bytesOut)) {
      resolvedBytesOut = bytesOut;
      for (long offset : TEST_OFFSETS_1) {
        dataOut.writeLong(offset);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    TEST_OFFSETS_1_BYTES = resolvedBytesOut.toByteArray();
  }

  private static final int SHUFFLE_ID = 0;
  private static final int MAP_ID = 0;
  private static final long ATTEMPT_ID = 0L;
  private static final MapOutputId ALL_IDS = new MapOutputId(SHUFFLE_ID, MAP_ID, ATTEMPT_ID);

  @Mock
  private ShuffleDriverEndpointRef driverEndpointRef;

  private InMemoryLocalDownloadShuffleFileSystem localShuffleFs;
  private InMemoryRemoteShuffleFileSystem remoteShuffleFs;
  private DefaultPartitionOffsetsFetcher offsetsFetcherUnderTest;
  private DeterministicScheduler cleanupScheduler;

  @Before
  public void before() {
    MockitoAnnotations.initMocks(this);
    when(driverEndpointRef.isShuffleRegistered(SHUFFLE_ID)).thenReturn(true);
    localShuffleFs = new InMemoryLocalDownloadShuffleFileSystem();
    remoteShuffleFs = new InMemoryRemoteShuffleFileSystem();
    cleanupScheduler = new DeterministicScheduler();
    remoteShuffleFs.putIndexFile(SHUFFLE_ID, MAP_ID, ATTEMPT_ID, TEST_OFFSETS_1_BYTES);
  }

  @Test
  public void testFetchingDirectlyFromRemoteStorage() {
    offsetsFetcherUnderTest = new DefaultPartitionOffsetsFetcher(
        localShuffleFs,
        remoteShuffleFs,
        cleanupScheduler,
        driverEndpointRef,
        128,
        128,
        false);
    offsetsFetcherUnderTest.startCleaningIndexFiles();
    verifyCorrectOffsets();
    assertThat(localShuffleFs.doesLocalIndexFileExist(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)).isFalse();
  }

  @Test
  public void testFetchingToDisk() {
    offsetsFetcherUnderTest = new DefaultPartitionOffsetsFetcher(
        localShuffleFs,
        remoteShuffleFs,
        cleanupScheduler,
        driverEndpointRef,
        128,
        128,
        true);
    offsetsFetcherUnderTest.startCleaningIndexFiles();
    verifyCorrectOffsets();
    assertThat(localShuffleFs.indexFileReadCounts().get(ALL_IDS))
        .isEqualTo(TEST_OFFSETS_1.length - 1);
    assertThat(remoteShuffleFs.indexFileReadCounts().get(ALL_IDS)).isEqualTo(1);
    assertThat(localShuffleFs.doesLocalIndexFileExist(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)).isTrue();
    assertThat(localShuffleFs.readAllIndexFileBytes(SHUFFLE_ID, MAP_ID, ATTEMPT_ID))
        .containsExactly(TEST_OFFSETS_1_BYTES);
  }

  @Test
  public void testCleanupLocalFiles() {
    offsetsFetcherUnderTest = new DefaultPartitionOffsetsFetcher(
        localShuffleFs,
        remoteShuffleFs,
        cleanupScheduler,
        driverEndpointRef,
        128,
        128,
        true);
    offsetsFetcherUnderTest.startCleaningIndexFiles();
    offsetsFetcherUnderTest.fetchPartitionOffsets(SHUFFLE_ID, MAP_ID, ATTEMPT_ID, 0);
    assertThat(localShuffleFs.doesLocalIndexFileExist(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)).isTrue();
    when(driverEndpointRef.isShuffleRegistered(SHUFFLE_ID)).thenReturn(true).thenReturn(false);
    cleanupScheduler.tick(30, TimeUnit.SECONDS);
    assertThat(localShuffleFs.doesLocalIndexFileExist(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)).isTrue();
    cleanupScheduler.tick(30, TimeUnit.SECONDS);
    assertThat(localShuffleFs.doesLocalIndexFileExist(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)).isFalse();
  }

  @Test
  public void testCleaningLocalFilesCrashesAndContinues() {
    offsetsFetcherUnderTest = new DefaultPartitionOffsetsFetcher(
        localShuffleFs,
        remoteShuffleFs,
        cleanupScheduler,
        driverEndpointRef,
        128,
        128,
        true);
    offsetsFetcherUnderTest.startCleaningIndexFiles();
    offsetsFetcherUnderTest.fetchPartitionOffsets(SHUFFLE_ID, MAP_ID, ATTEMPT_ID, 0);
    when(driverEndpointRef.isShuffleRegistered(SHUFFLE_ID))
        .thenThrow(RuntimeException.class)
        .thenReturn(false);
    cleanupScheduler.tick(30, TimeUnit.SECONDS);
    assertThat(localShuffleFs.doesLocalIndexFileExist(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)).isTrue();
    cleanupScheduler.tick(30, TimeUnit.SECONDS);
    assertThat(localShuffleFs.doesLocalIndexFileExist(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)).isFalse();
  }

  private void verifyCorrectOffsets() {
    for (int i = 0; i < TEST_OFFSETS_1.length - 1; i++) {
      PartitionOffsets expected = PartitionOffsets.of(TEST_OFFSETS_1[i], TEST_OFFSETS_1[i + 1]);
      PartitionOffsets actual = offsetsFetcherUnderTest.fetchPartitionOffsets(
          SHUFFLE_ID, MAP_ID, ATTEMPT_ID, i);
      assertThat(actual).isEqualTo(expected);
    }
  }
}
