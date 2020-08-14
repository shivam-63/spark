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

package org.apache.spark.palantir.shuffle.async

import scala.collection.JavaConverters._

import org.apache.spark.palantir.shuffle.async.metadata.{MapOutputId, ShuffleStorageState}
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.storage.BlockManagerId

/**
 * Represents a hook to the driver to query about the metadata of shuffle files, primarily their
 * states and possible merge ids.
 */
trait ShuffleDriverEndpointRef {
  def isShuffleRegistered(shuffleId: Int): Boolean

  def getNextMergeId: Long

  def registerLocallyWrittenMapOutput(mapOutputId: MapOutputId): Unit

  def registerMergedMapOutput(mapOutputIds: java.util.List[MapOutputId], mergeId: Long): Unit

  def registerUnmergedMapOutput(mapOutputId: MapOutputId): Unit

  def getShuffleStorageState(mapOutputId: MapOutputId): ShuffleStorageState

  def getShuffleStorageStates(shuffleId: Int): java.util.Map[MapOutputId, ShuffleStorageState]


  def blacklistExecutor(blockManagerId: BlockManagerId)
}

final class JavaShuffleDriverEndpointRef(
    shuffleDriverEndpointRef: RpcEndpointRef,
    executorLocation: BlockManagerId) extends ShuffleDriverEndpointRef {

  override def isShuffleRegistered(shuffleId: Int): Boolean = {
    shuffleDriverEndpointRef.askSync[Boolean](IsShuffleRegistered(shuffleId))
  }

  override def getNextMergeId: Long = {
    shuffleDriverEndpointRef.askSync[Long](GetNextMergeId)
  }

  override def registerMergedMapOutput(
      mapOutputIds: java.util.List[MapOutputId], mergeId: Long): Unit = {
    shuffleDriverEndpointRef.send(RegisterMergedMapOutput(mapOutputIds.asScala, mergeId))
  }

  override def registerUnmergedMapOutput(mapOutputId: MapOutputId): Unit = {
    shuffleDriverEndpointRef.send(RegisterUnmergedMapOutput(mapOutputId))
  }

  override def blacklistExecutor(blockManagerId: BlockManagerId): Unit = {
    // Don't call localShuffleStorageStates, since we should never be blacklisting ourselves...
    shuffleDriverEndpointRef.send(BlacklistExecutor(blockManagerId))
  }

  override def getShuffleStorageState(mapOutputId: MapOutputId): ShuffleStorageState = {
    shuffleDriverEndpointRef.askSync[ShuffleStorageState](GetShuffleStorageState(mapOutputId))
  }

  override def getShuffleStorageStates(shuffleId: Int)
      : java.util.Map[MapOutputId, ShuffleStorageState] = {
    shuffleDriverEndpointRef.askSync[java.util.Map[MapOutputId, ShuffleStorageState]](
      GetShuffleStorageStates(shuffleId))
  }

  override def registerLocallyWrittenMapOutput(mapOutputId: MapOutputId): Unit = {
    shuffleDriverEndpointRef.send(RegisterLocallyWrittenMapOutput(executorLocation, mapOutputId))
  }
}
