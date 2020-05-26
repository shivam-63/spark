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

package org.apache.spark.palantir.shuffle.async.reader;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.util.concurrent.SettableFuture;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.apache.spark.api.java.Optional;
import org.apache.spark.palantir.shuffle.async.client.ShuffleClient;
import org.apache.spark.palantir.shuffle.async.metrics.HadoopFetcherIteratorMetrics;
import org.apache.spark.shuffle.FetchFailedException;
import org.apache.spark.shuffle.api.ShuffleBlockInfo;
import org.apache.spark.shuffle.api.ShuffleBlockInputStream;
import org.apache.spark.storage.BlockManagerId;
import org.apache.spark.storage.ShuffleBlockId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class DefaultHadoopFetcherIteratorTest {

  @Mock
  private ShuffleClient shuffleClient;
  @Mock
  private HadoopFetcherIteratorMetrics metrics;
  @Mock
  private ShuffleBlockInputStream inputStream;

  private static final int SHUFFLE_ID = 0;
  private static final int MAP_ID = 10;
  private static final int REDUCE_ID = 4;
  private static final long ATTEMPT_ID = 3;
  private static final long BLOCK_LENGTH = 10;
  private static final ShuffleBlockId SHUFFLE_BLOCK_ID =
      ShuffleBlockId.apply(SHUFFLE_ID, MAP_ID, REDUCE_ID);
  private static final BlockManagerId BLOCK_MANAGER_ID = BlockManagerId.apply("host", 1234);
  private static final ShuffleBlockInfo SHUFFLE_BLOCK_INFO =
      new ShuffleBlockInfo(
          SHUFFLE_ID, MAP_ID, REDUCE_ID, BLOCK_LENGTH, ATTEMPT_ID, Optional.of(BLOCK_MANAGER_ID));

  private DefaultHadoopFetcherIterator fetcherIterator;
  private SettableFuture<Supplier<InputStream>> inputStreamFuture;
  private Set<ShuffleBlockInfo> shuffleBlocksToFetch;

  @Before
  public void before() {
    MockitoAnnotations.initMocks(this);
    inputStreamFuture = SettableFuture.create();
    when(shuffleClient.getBlockData(
        SHUFFLE_ID, MAP_ID, REDUCE_ID, ATTEMPT_ID))
        .thenReturn(inputStreamFuture);
    shuffleBlocksToFetch = new HashSet<>();
    shuffleBlocksToFetch.add(SHUFFLE_BLOCK_INFO);
    fetcherIterator = new DefaultHadoopFetcherIterator(
        shuffleClient, shuffleBlocksToFetch, metrics);
  }

  @Test
  public void testGetsBlock() {
    fetcherIterator.fetchDataFromHadoop();
    inputStreamFuture.set(() -> inputStream);
    ShuffleBlockInputStream actualInputStream = fetcherIterator.next();
    verify(shuffleClient).getBlockData(
        SHUFFLE_ID, MAP_ID, REDUCE_ID, ATTEMPT_ID);
    assertThat(actualInputStream.getBlockId()).isEqualTo(SHUFFLE_BLOCK_ID);

    // there's nothing else left
    assertThatThrownBy(() -> fetcherIterator.next())
        .hasMessageContaining("Next should not be called because iterator is empty");
  }

  @Test
  public void testFetchFails() {
    fetcherIterator.fetchDataFromHadoop();
    inputStreamFuture.setException(new Exception("Could not fetch from remote storage."));
    assertThatThrownBy(() -> fetcherIterator.next())
        .isInstanceOf(FetchFailedException.class)
        .hasMessageContaining("Exception thrown when fetching data from remote storage.");
  }

  @Test
  public void testFuturesCancelledWhenStopped() {
    fetcherIterator.fetchDataFromHadoop();
    // stop the fetcher iterator
    fetcherIterator.cleanup();
    assertThat(inputStreamFuture.isCancelled()).isTrue();
  }

  @Test
  public void testFetchedFailedStreamsInCleanup() {
    fetcherIterator.fetchDataFromHadoop();
    inputStreamFuture.setException(new Exception("Could not fetch from remote storage."));
    fetcherIterator.cleanup();
  }

  @Test
  public void testClosingMultipleThings() {
    ShuffleBlockInfo shuffleBlock1 = new ShuffleBlockInfo(
        SHUFFLE_ID,
        MAP_ID,
        1,
        BLOCK_LENGTH,
        0,
        Optional.of(BLOCK_MANAGER_ID));
    ShuffleBlockInfo shuffleBlock2 = new ShuffleBlockInfo(
        SHUFFLE_ID,
        MAP_ID,
        2,
        BLOCK_LENGTH,
        0,
        Optional.of(BLOCK_MANAGER_ID));
    SettableFuture<Supplier<InputStream>> inputStreamFuture1 = SettableFuture.create();
    SettableFuture<Supplier<InputStream>> inputStreamFuture2 = SettableFuture.create();
    shuffleBlocksToFetch.add(shuffleBlock1);
    shuffleBlocksToFetch.add(shuffleBlock2);
    when(shuffleClient.getBlockData(
        SHUFFLE_ID, MAP_ID, 1, 0))
        .thenReturn(inputStreamFuture1);
    when(shuffleClient.getBlockData(
        SHUFFLE_ID, MAP_ID, 2, 0))
        .thenReturn(inputStreamFuture2);
    fetcherIterator.fetchDataFromHadoop();

    inputStreamFuture1.set(() -> inputStream);
    inputStreamFuture.setException(new Exception("Could not fetch from remote storage."));

    fetcherIterator.cleanup();
    assertThat(inputStreamFuture2).isCancelled();
  }
}
