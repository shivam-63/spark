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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.spark.io.CompressionCodec;
import org.apache.spark.palantir.shuffle.async.FetchFailedExceptionThrower;
import org.apache.spark.palantir.shuffle.async.ShuffleDriverEndpointRef;
import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;
import org.apache.spark.palantir.shuffle.async.metadata.OnExecutorOnly;
import org.apache.spark.palantir.shuffle.async.metadata.OnRemoteOnly;
import org.apache.spark.serializer.SerializerManager;
import org.apache.spark.shuffle.FetchFailedException;
import org.apache.spark.shuffle.api.ShuffleBlockInfo;
import org.apache.spark.shuffle.api.ShuffleBlockInputStream;
import org.apache.spark.storage.BlockId;
import org.apache.spark.storage.BlockManagerId;
import org.apache.spark.storage.ShuffleBlockId;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import scala.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class ExecutorThenHadoopFetcherIteratorTest {

  private static final BlockManagerId BLOCK_MANAGER_ID = BlockManagerId.apply("host", 1234);
  private static final ShuffleBlockId BLOCK_1 = ShuffleBlockId.apply(0, 1, 1);
  private static final ShuffleBlockInfo BLOCK_INFO_1 =
      new ShuffleBlockInfo(
          BLOCK_1.shuffleId(), BLOCK_1.mapId(), BLOCK_1.reduceId(), 10, 1,
          org.apache.spark.api.java.Optional.of(BLOCK_MANAGER_ID));

  private static final ShuffleBlockId BLOCK_2 = ShuffleBlockId.apply(0, 2, 1);
  private static final ShuffleBlockInfo BLOCK_INFO_2 =
      new ShuffleBlockInfo(
          BLOCK_2.shuffleId(), BLOCK_2.mapId(), BLOCK_2.reduceId(), 10, 1,
          org.apache.spark.api.java.Optional.of(BLOCK_MANAGER_ID));

  @Mock
  private SerializerManager serializerManager;
  @Mock
  private CompressionCodec compressionCodec;
  @Mock
  private ShuffleDriverEndpointRef driverEndpointRef;

  private TestHadoopFetcherIteratorFactory testHadoopFetcherIteratorFactory;

  @Before
  public void before() {
    MockitoAnnotations.initMocks(this);
    testHadoopFetcherIteratorFactory = null;
  }

  @Test
  public void testRetrievesFromExecutorOnly() {
    ExecutorThenHadoopFetcherIterator iteratorUnderTest = getIteratorUnderTest(
        new FetchFailedThrowingStreamList().addStream(BLOCK_INFO_1),
        ImmutableSet.of());
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThat(iteratorUnderTest.next().getBlockId()).isEqualTo(BLOCK_1);
    assertThat(iteratorUnderTest.hasNext()).isFalse();
  }

  @Test
  public void testRetrievesFromRemoteOnly() {
    ExecutorThenHadoopFetcherIterator iteratorUnderTest = getIteratorUnderTest(
        new FetchFailedThrowingStreamList(),
        ImmutableSet.of(BLOCK_INFO_1));
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThat(iteratorUnderTest.next().getBlockId()).isEqualTo(BLOCK_1);
    assertThat(iteratorUnderTest.hasNext()).isFalse();
  }

  @Test
  public void testEmpty() {
    ExecutorThenHadoopFetcherIterator iteratorUnderTest = getIteratorUnderTest(
        new FetchFailedThrowingStreamList(),
        ImmutableSet.of());
    assertThat(iteratorUnderTest.hasNext()).isFalse();
  }

  @Test
  public void testExecutorThenRemote() {
    ExecutorThenHadoopFetcherIterator iteratorUnderTest = getIteratorUnderTest(
        new FetchFailedThrowingStreamList().addStream(BLOCK_INFO_1),
        ImmutableSet.of(BLOCK_INFO_2));
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThat(iteratorUnderTest.next().getBlockId()).isEqualTo(BLOCK_1);
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThat(iteratorUnderTest.next().getBlockId()).isEqualTo(BLOCK_2);
    assertThat(iteratorUnderTest.hasNext()).isFalse();
    assertThat(testHadoopFetcherIteratorFactory.getHadoopFetcherIterator().returnedBlockIds())
        .containsExactly(BLOCK_2);
  }

  @Test
  public void testExecutorFailsAndBlocksDoesntExist() {
    when(driverEndpointRef.getShuffleStorageStates(0)).thenReturn(ImmutableMap.of());
    ExecutorThenHadoopFetcherIterator iteratorUnderTest = getIteratorUnderTest(
        new FetchFailedThrowingStreamList()
            .addThrownFetchFailed(BLOCK_INFO_1),
        ImmutableSet.of(),
        new TestHadoopFetcherIteratorFactory(Collections.emptySet()));
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThatThrownBy(iteratorUnderTest::next).isInstanceOf(FetchFailedException.class);
    verify(driverEndpointRef, times(1)).blacklistExecutor(
        BLOCK_INFO_1.getShuffleLocation().get());
  }

  @Test
  public void testExecutorFailsAndBlocksNotOnRemote() {
    when(driverEndpointRef.getShuffleStorageStates(0)).thenReturn(ImmutableMap.of(
        new MapOutputId(BLOCK_INFO_1.getShuffleId(), BLOCK_INFO_1.getMapId(),
            BLOCK_INFO_1.getMapTaskAttemptId()),
        new OnExecutorOnly(BlockManagerId.apply("host", 1234))));
    ExecutorThenHadoopFetcherIterator iteratorUnderTest = getIteratorUnderTest(
        new FetchFailedThrowingStreamList()
            .addThrownFetchFailed(BLOCK_INFO_1),
        ImmutableSet.of(),
        new TestHadoopFetcherIteratorFactory(Collections.emptySet()));
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThatThrownBy(iteratorUnderTest::next).isInstanceOf(FetchFailedException.class);
    verify(driverEndpointRef, times(1)).blacklistExecutor(
        BLOCK_INFO_1.getShuffleLocation().get());
  }

  @Test
  public void testExecutorFailsAndBlocksOnRemote() {
    when(driverEndpointRef.getShuffleStorageStates(0)).thenReturn(ImmutableMap.of(
        new MapOutputId(BLOCK_INFO_1.getShuffleId(), BLOCK_INFO_1.getMapId(),
            BLOCK_INFO_1.getMapTaskAttemptId()),
        new OnRemoteOnly(Option.empty()),
        new MapOutputId(BLOCK_INFO_2.getShuffleId(), BLOCK_INFO_2.getMapId(),
            BLOCK_INFO_2.getMapTaskAttemptId()),
        new OnRemoteOnly(Option.empty())));
    ExecutorThenHadoopFetcherIterator iteratorUnderTest = getIteratorUnderTest(
        new FetchFailedThrowingStreamList()
            .addThrownFetchFailed(BLOCK_INFO_1)
            .addThrownFetchFailed(BLOCK_INFO_2),
        ImmutableSet.of(BLOCK_INFO_2),
        new TestHadoopFetcherIteratorFactory(ImmutableSet.of(BLOCK_INFO_1, BLOCK_INFO_2)));
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThat(iteratorUnderTest.next().getBlockId()).isEqualTo(BLOCK_2);
    assertThat(iteratorUnderTest.hasNext()).isTrue();
    assertThat(iteratorUnderTest.next().getBlockId()).isEqualTo(BLOCK_1);
    assertThat(iteratorUnderTest.hasNext()).isFalse();
    assertThat(testHadoopFetcherIteratorFactory.getHadoopFetcherIterator().returnedBlockIds())
        .containsExactly(BLOCK_1, BLOCK_2);
    verify(driverEndpointRef, times(1)).blacklistExecutor(
        BLOCK_INFO_1.getShuffleLocation().get());
  }

  private ExecutorThenHadoopFetcherIterator getIteratorUnderTest(
      FetchFailedThrowingStreamList streamsFromExecutors,
      Set<ShuffleBlockInfo> initialRemoteBlocksToFetch,
      TestHadoopFetcherIteratorFactory hadoopFetcherIteratorFactory) {
    this.testHadoopFetcherIteratorFactory = hadoopFetcherIteratorFactory;
    return new ExecutorThenHadoopFetcherIterator(
        0,
        streamsFromExecutors.iterator(),
        ImmutableSet.copyOf(streamsFromExecutors.blockInfos()),
        false,
        serializerManager,
        compressionCodec,
        ImmutableSet.copyOf(initialRemoteBlocksToFetch),
        hadoopFetcherIteratorFactory,
        driverEndpointRef);
  }

  private ExecutorThenHadoopFetcherIterator getIteratorUnderTest(
      FetchFailedThrowingStreamList streamsFromExecutors,
      Set<ShuffleBlockInfo> remoteStorageFetchFailedBlocks) {
    testHadoopFetcherIteratorFactory = new TestHadoopFetcherIteratorFactory(
        remoteStorageFetchFailedBlocks);
    return getIteratorUnderTest(
        streamsFromExecutors,
        remoteStorageFetchFailedBlocks,
        testHadoopFetcherIteratorFactory);
  }

  private static ShuffleBlockInputStream toBlockInputStream(ShuffleBlockInfo block) {
    return new ShuffleBlockInputStream(
        ShuffleBlockId.apply(block.getShuffleId(), block.getMapId(), block.getReduceId()),
        new ByteArrayInputStream(new byte[]{0, 1, 2, 3, 4, 5}));
  }

  private static ShuffleBlockInputStream toThrownFetchFailed(ShuffleBlockInfo block) {
    return FetchFailedExceptionThrower.throwFetchFailedException(
        block.getShuffleId(),
        block.getMapId(),
        block.getReduceId(),
        block.getShuffleLocation().orNull(),
        "Manually triggered fetch failed.",
        null);
  }

  private static Supplier<ShuffleBlockInputStream> toBlockInputStreamSupplier(
      ShuffleBlockInfo block) {
    return () -> toBlockInputStream(block);
  }

  private static Supplier<ShuffleBlockInputStream> toThrowingFetchFailedSupplier(
      ShuffleBlockInfo block) {
    return () -> toThrownFetchFailed(block);
  }

  private static final class FetchFailedThrowingStreamList
      implements Iterable<ShuffleBlockInputStream> {

    private final List<ShuffleBlockInfo> blockInfos = new ArrayList<>();
    private final List<Supplier<ShuffleBlockInputStream>> maybeThrowingStreams = new ArrayList<>();

    public FetchFailedThrowingStreamList addStream(ShuffleBlockInfo blockInfo) {
      maybeThrowingStreams.add(toBlockInputStreamSupplier(blockInfo));
      blockInfos.add(blockInfo);
      return this;
    }

    public FetchFailedThrowingStreamList addThrownFetchFailed(ShuffleBlockInfo blockInfo) {
      maybeThrowingStreams.add(toThrowingFetchFailedSupplier(blockInfo));
      blockInfos.add(blockInfo);
      return this;
    }

    @Override
    public Iterator<ShuffleBlockInputStream> iterator() {
      return Iterators.transform(maybeThrowingStreams.iterator(), Supplier::get);
    }

    public List<ShuffleBlockInfo> blockInfos() {
      return blockInfos;
    }
  }

  private static final class TestHadoopFetcherIteratorFactory
      implements HadoopFetcherIteratorFactory {

    private final Collection<ShuffleBlockInfo> expectedBlocks;
    private TestHadoopFetcherIterator hadoopFetcherIterator;

    TestHadoopFetcherIteratorFactory(Collection<ShuffleBlockInfo> expectedBlocks) {
      this.expectedBlocks = expectedBlocks;
    }

    @Override
    public HadoopFetcherIterator createFetcherIteratorForBlocks(
        Collection<ShuffleBlockInfo> blocks) {
      assertThat(expectedBlocks).containsExactlyInAnyOrder(
          blocks.toArray(new ShuffleBlockInfo[blocks.size()]));
      hadoopFetcherIterator = new TestHadoopFetcherIterator(new HashSet<>(blocks));
      return hadoopFetcherIterator;
    }

    public TestHadoopFetcherIterator getHadoopFetcherIterator() {
      return hadoopFetcherIterator;
    }
  }

  private static final class TestHadoopFetcherIterator implements HadoopFetcherIterator {

    private final Set<ShuffleBlockInfo> remoteFetchFailedBlocks;
    private final Set<BlockId> returnedBlockIds = new HashSet<>();
    private final Set<ShuffleBlockInfo> initializedBlocks = new HashSet<>();
    private Iterator<ShuffleBlockInputStream> backingIterator;

    TestHadoopFetcherIterator(Set<ShuffleBlockInfo> remoteBlocksToFetch) {
      this.remoteFetchFailedBlocks = remoteBlocksToFetch;
      this.initializedBlocks.addAll(remoteFetchFailedBlocks);
      this.backingIterator = ImmutableList.copyOf(initializedBlocks)
          .stream()
          .map(block -> toBlockInputStream(block))
          .iterator();
    }

    @Override
    public void cleanup() {
    }

    @Override
    public boolean hasNext() {
      return backingIterator.hasNext();
    }

    @Override
    public ShuffleBlockInputStream next() {
      ShuffleBlockInputStream next = backingIterator.next();
      returnedBlockIds.add(next.getBlockId());
      return next;
    }

    public Set<BlockId> returnedBlockIds() {
      return returnedBlockIds;
    }
  }
}
