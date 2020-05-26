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

import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageStateTracker
import org.apache.spark.rpc.{RpcCallContext, RpcEnv, ThreadSafeRpcEndpoint}

/**
 * Communication hook on the driver for receiving queries from executors and delegating them to the
 * {@link ShuffleStorageStateTracker}.
 */
final class AsyncShuffleUploadDriverEndpoint(
    override val rpcEnv: RpcEnv,
    shuffleStorageStateTracker: ShuffleStorageStateTracker) extends ThreadSafeRpcEndpoint {

  override def receiveAndReply(context: RpcCallContext)
      : PartialFunction[Any, Unit] = {
    case IsShuffleRegistered(shuffleId) =>
      context.reply(shuffleStorageStateTracker.isShuffleRegistered(shuffleId))
    case GetNextMergeId => context.reply(shuffleStorageStateTracker.getNextMergeId)
    case GetShuffleStorageStates(shuffleId) =>
      context.reply(shuffleStorageStateTracker.getShuffleStorageStates(shuffleId))
    case GetShuffleStorageState(mapOutputId) =>
      context.reply(shuffleStorageStateTracker.getShuffleStorageState(mapOutputId))
  }

  override def receive(): PartialFunction[Any, Unit] = {
    case RegisterLocallyWrittenMapOutput(executorLocation, mapOutputId) =>
      shuffleStorageStateTracker.registerLocallyWrittenMapOutput(executorLocation, mapOutputId)
    case RegisterMergedMapOutput(mapOutputIds, mergeId) =>
      mapOutputIds.foreach(shuffleStorageStateTracker.registerMergedBackedUpMapOutput(_, mergeId))
    case RegisterUnmergedMapOutput(mapOutputId) =>
      shuffleStorageStateTracker.registerUnmergedBackedUpMapOutput(mapOutputId)
    case BlacklistExecutor(blockManagerId) =>
      shuffleStorageStateTracker.blacklistExecutor(blockManagerId)
  }
}

object AsyncShuffleUploadDriverEndpoint {
  val NAME = "async-shuffle-driver-endpoint"

  def create(rpcEnv: RpcEnv, shuffleStorageStateTracker: ShuffleStorageStateTracker)
      : AsyncShuffleUploadDriverEndpoint = {
    new AsyncShuffleUploadDriverEndpoint(rpcEnv, shuffleStorageStateTracker)
  }
}
