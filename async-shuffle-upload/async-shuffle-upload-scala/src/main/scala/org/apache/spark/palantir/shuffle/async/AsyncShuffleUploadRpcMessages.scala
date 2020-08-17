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

import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId
import org.apache.spark.storage.BlockManagerId

sealed trait AsyncShuffleUploadRpcMessage

case class IsShuffleRegistered(shuffleId: Int) extends AsyncShuffleUploadRpcMessage

case object GetNextMergeId extends AsyncShuffleUploadRpcMessage

case class RegisterLocallyWrittenMapOutput(
    executorLocation: BlockManagerId, mapOutput: MapOutputId) extends AsyncShuffleUploadRpcMessage

case class RegisterUnmergedMapOutput(mapOutput: MapOutputId) extends AsyncShuffleUploadRpcMessage

case class RegisterMergedMapOutput(mapOutputs: Seq[MapOutputId], mergeId: Long)
  extends AsyncShuffleUploadRpcMessage

case class GetShuffleStorageState(mapOutputId: MapOutputId) extends AsyncShuffleUploadRpcMessage

case class GetShuffleStorageStates(shuffleId: Int) extends AsyncShuffleUploadRpcMessage

case class BlacklistExecutor(blockManagerId: BlockManagerId) extends AsyncShuffleUploadRpcMessage

