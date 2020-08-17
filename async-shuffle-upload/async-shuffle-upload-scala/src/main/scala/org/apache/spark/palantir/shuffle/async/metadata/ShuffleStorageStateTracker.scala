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

import java.lang.{Integer => JInt}
import java.util.{Map => JMap}
import java.util.concurrent.atomic.AtomicLong

import scala.compat.java8.FunctionConverters._

import com.google.common.collect.{ImmutableMap, Maps}
import com.palantir.logsafe.SafeArg
import com.palantir.logsafe.exceptions.SafeIllegalStateException

import org.apache.spark.storage.BlockManagerId

/**
 * Module for managing the states of shuffle files.
 * <p>
 * Map output files can reside either on the executor, on remote storage, both, or neither.
 * The places where the files are available  primarily affects the read pattern on reducers. For
 * example, files that are on remote storage but not on the executor can obviously only be read
 * from the remote storage location.
 * <p>
 * A map output is first written to local disk, and the map task registers the map output via
 * {@link #registerLocallyWrittenMapOutput}. Afterwards, the file is backed up to remote
 * storage. When the backup is complete, the mapper calls {@link #registerMergedBackedUpMapOutput}
 * or {@link #registerUnmergedBackedUpMapOutput}, depending on if the map output is stored via the
 * merging storage strategy or the basic storage strategy, respectively. The block should
 * transition states from being stored on the executor, to being stored on both the executor and
 * the remote storage.
 * <p>
 * When an executor attempts to read a shuffle block from an executor and then receives a fetch
 * failure in response, the reducer will call {@link #blacklistExecutor} with the source location.
 * At this point, for each block that was stored on that executor:
 * <p>
 * a) If the block was registered as backed up, the block is marked as only being available in the
 *    remote storage location. Or,
 * <p>
 * b) If the block was not registered as being backed up, the block is removed and is considered
 *    unregistered. Such a block would need to be recomputed by another attempt of the map task.
 * <p>
 * Mutation operations are always treated as state transitions - a mutation can cause a
 * non-existing block to enter an initial state, or for an existing block to transition from an
 * initial state to a following state ({@link #updateStorageState}).
 */
class ShuffleStorageStateTracker {

  import ShuffleStorageStateTracker._

  private val states = Maps.newConcurrentMap[JInt, JMap[MapOutputId, ShuffleStorageState]]
  private val nextMergeId = new AtomicLong(0L)

  def getNextMergeId: Long = nextMergeId.getAndIncrement()

  def registerShuffle(shuffleId: Int): Unit = {
    states.putIfAbsent(shuffleId, Maps.newConcurrentMap[MapOutputId, ShuffleStorageState])
  }

  def unregisterShuffle(shuffleId: Int): Unit = {
    states.remove(shuffleId)
  }

  def registerLocallyWrittenMapOutput(
      executorLocation: BlockManagerId,
      mapOutputId: MapOutputId): Unit = {
    updateStorageState(
      mapOutputId,
      OnExecutorOnly(executorLocation),
      prev => {
        throw new SafeIllegalStateException(
          "Map output should only be registered as locally written as an initial state.",
          mapOutputIdArg(mapOutputId),
          invalidPrevStateArg(prev)
        )
      })
  }

  def registerUnmergedBackedUpMapOutput(mapOutputId: MapOutputId): Unit = {
    updateStorageState(
      mapOutputId,
      defaultStateFunc = {
        throw mapOutputNotWrittenLocallyBeforeBackedUp(mapOutputId)
      },
      nextStateFunc = {
        case OnExecutorOnly(executorLocation) => OnExecutorAndRemote(executorLocation)
        case invalidPrevState: ShuffleStorageState =>
          throw mapOutputNotWrittenLocallyBeforeBackedUp(mapOutputId, Some(invalidPrevState))
      })
  }

  def registerMergedBackedUpMapOutput(mapOutputId: MapOutputId, mergeId: Long): Unit = {
    updateStorageState(
      mapOutputId,
      defaultStateFunc = {
        throw mapOutputNotWrittenLocallyBeforeBackedUp(mapOutputId)
      },
      nextStateFunc = {
        case OnExecutorOnly(executorLocation) => OnExecutorAndRemote(executorLocation, mergeId)
        case invalidPrevState: ShuffleStorageState =>
          throw mapOutputNotWrittenLocallyBeforeBackedUp(mapOutputId, Some(invalidPrevState))
      })
  }

  def markLocalMapOutputAsDeleted(mapOutputId: MapOutputId): Unit = {
    updateStorageState(
      mapOutputId,
      defaultStateFunc = {
        throw mapOutputNotBackedUpBeforeDeletedLocally(mapOutputId)
      },
      nextStateFunc = {
        case OnExecutorAndRemote(_, mergeId) => OnRemoteOnly(mergeId)
        case invalidPrevState: ShuffleStorageState =>
          throw mapOutputNotBackedUpBeforeDeletedLocally(mapOutputId, Some(invalidPrevState))
      })
  }

  def isShuffleRegistered(shuffleId: Int): Boolean = states.containsKey(shuffleId)

  def getShuffleStorageState(mapOutputId: MapOutputId): ShuffleStorageState = {
    states.getOrDefault(mapOutputId.shuffleId, NO_MAP_OUTPUTS)
      .getOrDefault(mapOutputId, Unregistered)
  }

  def getShuffleStorageStates(shuffleId: Int): JMap[MapOutputId, ShuffleStorageState] = {
    ImmutableMap.copyOf(states.getOrDefault(shuffleId, NO_MAP_OUTPUTS))
  }

  def blacklistExecutor(blacklistedLocation: BlockManagerId): Unit = {
    states.replaceAll(
      asJavaBiFunction { (_: JInt, mapOutputs: JMap[MapOutputId, ShuffleStorageState]) =>
        mapOutputs.replaceAll(
          asJavaBiFunction { (_, state) =>
            state match {
              case storedOnExecutor: StoredOnExecutor
                  if storedOnExecutor.executorLocation == blacklistedLocation =>
                getNextStateWhenLocationRemoved(storedOnExecutor)
              case other: ShuffleStorageState => other
            }
          })
        mapOutputs
      })
  }

  private def getNextStateWhenLocationRemoved(prevState: StoredOnExecutor): ShuffleStorageState = {
    prevState match {
      case OnExecutorOnly(_) => Unregistered
      case OnExecutorAndRemote(_, mergeId) => OnRemoteOnly(mergeId)
    }
  }

  private def updateStorageState(
      mapOutputId: MapOutputId,
      defaultStateFunc: => ShuffleStorageState,
      nextStateFunc: ShuffleStorageState => ShuffleStorageState): Unit = {
    states.computeIfPresent(
      mapOutputId.shuffleId,
      asJavaBiFunction { (_, mapOutputs: JMap[MapOutputId, ShuffleStorageState]) =>
        mapOutputs.compute(
          mapOutputId,
          asJavaBiFunction { (_, prevState) =>
            Option(prevState)
              .map(nextStateFunc)
              .getOrElse(defaultStateFunc)
          })
        mapOutputs
      })
  }
}

private object ShuffleStorageStateTracker {
  private val NO_MAP_OUTPUTS = ImmutableMap.of[MapOutputId, ShuffleStorageState]()

  def mapOutputNotWrittenLocallyBeforeBackedUp(
      mapOutputId: MapOutputId,
      invalidPrevState: Option[ShuffleStorageState] = None): SafeIllegalStateException = {
    val errMsg = "Map output should not be initially marked as backed up; should be registered" +
      " as being on the executor only first."
    invalidPrevState.map { prev =>
      new SafeIllegalStateException(
        errMsg,
        mapOutputIdArg(mapOutputId),
        invalidPrevStateArg(prev))
    }.getOrElse(new SafeIllegalStateException(errMsg, mapOutputIdArg(mapOutputId)))
  }

  def mapOutputNotBackedUpBeforeDeletedLocally(
      mapOutputId: MapOutputId,
      invalidPrevState: Option[ShuffleStorageState] = None): SafeIllegalStateException = {
    val errMsg = "Map output should not be marked as deleted before it was marked as being" +
      " backed up."
    invalidPrevState.map { prev =>
      new SafeIllegalStateException(
        errMsg,
        mapOutputIdArg(mapOutputId),
        invalidPrevStateArg(prev))
    }.getOrElse(new SafeIllegalStateException(errMsg, mapOutputIdArg(mapOutputId)))
  }

  def mapOutputIdArg(mapOutputId: MapOutputId): SafeArg[MapOutputId] = {
    SafeArg.of("mapOutputId", mapOutputId)
  }

  def invalidPrevStateArg(invalidPrevState: ShuffleStorageState): SafeArg[ShuffleStorageState] = {
    SafeArg.of("invalidPrevState", invalidPrevState)
  }
}
