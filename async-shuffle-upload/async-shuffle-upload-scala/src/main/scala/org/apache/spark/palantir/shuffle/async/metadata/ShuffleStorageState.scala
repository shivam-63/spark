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

package org.apache.spark.palantir.shuffle.async.metadata

import java.lang.{Long => JLong}
import java.util.{Optional => JOptional}

import scala.compat.java8.OptionConverters._

import org.apache.spark.storage.BlockManagerId

sealed trait ShuffleStorageState {
  def visit[T](visitor: ShuffleStorageStateVisitor[T]): T = {
    this match {
      case Unregistered => visitor.unregistered()
      case OnExecutorOnly(executorLocation) => visitor.onExecutorOnly(executorLocation)
      case OnExecutorAndRemote(executorLocation, mergeId) =>
        visitor.onExecutorAndRemote(executorLocation, mergeId.map(long2Long).asJava)
      case OnRemoteOnly(mergeId) => visitor.onRemoteOnly(mergeId.map(long2Long).asJava)
    }
  }
}

sealed trait StoredOnExecutor extends ShuffleStorageState {
  def executorLocation: BlockManagerId
}

sealed trait StoredOnRemote extends ShuffleStorageState {
  def mergeId: Option[Long]

  def getMergeId: JOptional[JLong] = mergeId.map(long2Long).asJava
}

case object Unregistered extends ShuffleStorageState

case class OnExecutorOnly(executorLocation: BlockManagerId)
  extends ShuffleStorageState with StoredOnExecutor

case class OnExecutorAndRemote(executorLocation: BlockManagerId, mergeId: Option[Long] = None)
    extends StoredOnExecutor with StoredOnRemote

object OnExecutorAndRemote {
  def apply(executorLocation: BlockManagerId, mergeId: Long): OnExecutorAndRemote = {
    OnExecutorAndRemote(executorLocation, Some(mergeId))
  }
}

case class OnRemoteOnly(mergeId: Option[Long] = None) extends StoredOnRemote

object OnRemoteOnly {
  def apply(mergeId: Long): OnRemoteOnly = OnRemoteOnly(Some(mergeId))
}

/**
 * For Java interpretation of the state. Otherwise, one should use Scala pattern matching.
 */
trait ShuffleStorageStateVisitor[T] {

  def unregistered(): T

  def onExecutorOnly(executorLocation: BlockManagerId): T

  def onExecutorAndRemote(executorLocation: BlockManagerId, mergeId: JOptional[JLong]): T

  def onRemoteOnly(mergeId: JOptional[JLong]): T
}
