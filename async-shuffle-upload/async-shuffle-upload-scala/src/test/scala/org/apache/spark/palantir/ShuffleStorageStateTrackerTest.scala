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

package org.apache.spark.palantir

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, Matchers}
import org.scalatest.junit.JUnitRunner

import org.apache.spark.SparkFunSuite
import org.apache.spark.palantir.ShuffleStorageStateTrackerTest._
import org.apache.spark.palantir.shuffle.async.metadata.{MapOutputId, OnExecutorAndRemote, OnExecutorOnly, OnRemoteOnly, ShuffleStorageStateTracker, Unregistered}
import org.apache.spark.palantir.test.util.BlockManagerIdFactory

@RunWith(classOf[JUnitRunner])
class ShuffleStorageStateTrackerTest extends SparkFunSuite with BeforeAndAfter with Matchers {

  private var trackerUnderTest: ShuffleStorageStateTracker = _

  before {
    trackerUnderTest = new ShuffleStorageStateTracker()
  }

  test("Initial state is always unregistered.") {
    trackerUnderTest.getShuffleStorageState(MapOutputId(1, 2, 3)) shouldBe Unregistered
  }

  test("Register shuffle -> initial state for map outputs is unregistered.") {
    trackerUnderTest.registerShuffle(0)
    trackerUnderTest.getShuffleStorageState(MapOutputId(0, 1, 2)) shouldBe Unregistered
  }

  test("Unregistered -> registered locally -> registered backed up -> executor blacklisted") {
    trackerUnderTest.registerShuffle(0)
    trackerUnderTest.registerLocallyWrittenMapOutput(BM_ID_1, MAP_OUTPUT_ID_1)
    trackerUnderTest.registerLocallyWrittenMapOutput(BM_ID_2, MAP_OUTPUT_ID_2)
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_1) shouldBe OnExecutorOnly(BM_ID_1)
    trackerUnderTest.registerUnmergedBackedUpMapOutput(MAP_OUTPUT_ID_1)
    trackerUnderTest.registerUnmergedBackedUpMapOutput(MAP_OUTPUT_ID_2)
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_1) shouldBe OnExecutorAndRemote(BM_ID_1)
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_2) shouldBe OnExecutorAndRemote(BM_ID_2)
    trackerUnderTest.blacklistExecutor(BM_ID_1)
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_1) shouldBe OnRemoteOnly()
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_2) shouldBe OnExecutorAndRemote(BM_ID_2)
  }

  test("Unregistered -> registered locally -> executor blacklisted") {
    trackerUnderTest.registerShuffle(0)
    trackerUnderTest.registerLocallyWrittenMapOutput(BM_ID_1, MAP_OUTPUT_ID_1)
    trackerUnderTest.registerLocallyWrittenMapOutput(BM_ID_2, MAP_OUTPUT_ID_2)
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_1) shouldBe OnExecutorOnly(BM_ID_1)
    trackerUnderTest.blacklistExecutor(BM_ID_1)
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_1) shouldBe Unregistered
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_2) shouldBe OnExecutorOnly(BM_ID_2)
  }

  test("Unregistered -> registered locally -> registered backed up -> delete local file") {
    trackerUnderTest.registerShuffle(0)
    trackerUnderTest.registerLocallyWrittenMapOutput(BM_ID_1, MAP_OUTPUT_ID_1)
    trackerUnderTest.registerLocallyWrittenMapOutput(BM_ID_2, MAP_OUTPUT_ID_2)
    trackerUnderTest.registerUnmergedBackedUpMapOutput(MAP_OUTPUT_ID_1)
    trackerUnderTest.registerUnmergedBackedUpMapOutput(MAP_OUTPUT_ID_2)
    trackerUnderTest.markLocalMapOutputAsDeleted(MAP_OUTPUT_ID_1)
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_1) shouldBe OnRemoteOnly()
    trackerUnderTest.getShuffleStorageState(MAP_OUTPUT_ID_2) shouldBe OnExecutorAndRemote(BM_ID_2)
  }
}

private object ShuffleStorageStateTrackerTest {
  private val BLOCK_MANAGER_ID_PORT = 7077
  private val BM_ID_1 = BlockManagerIdFactory.createBlockManagerId("host1", BLOCK_MANAGER_ID_PORT)
  private val BM_ID_2 = BlockManagerIdFactory.createBlockManagerId("host2", BLOCK_MANAGER_ID_PORT)
  private val MAP_OUTPUT_ID_1 = MapOutputId(0, 0, 0)
  private val MAP_OUTPUT_ID_2 = MapOutputId(0, 1, 1)
}
