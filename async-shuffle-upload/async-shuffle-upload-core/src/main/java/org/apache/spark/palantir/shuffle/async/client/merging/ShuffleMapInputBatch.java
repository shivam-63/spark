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

package org.apache.spark.palantir.shuffle.async.client.merging;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.palantir.shuffle.async.metadata.MapOutputId;

public final class ShuffleMapInputBatch {
  private final List<ShuffleMapInput> inputBatch;
  private long totalDataSizeInBytes;
  private long earliestInputTimestamp;

  ShuffleMapInputBatch() {
    this.inputBatch = new ArrayList<>();
    this.totalDataSizeInBytes = 0L;
    this.earliestInputTimestamp = Long.MAX_VALUE;
  }

  public ShuffleMapInputBatch addInput(ShuffleMapInput newInput, long inputCreationTimestamp) {
    inputBatch.add(newInput);
    totalDataSizeInBytes += newInput.dataSizedInput().getStreamSizeInBytes();
    earliestInputTimestamp = Long.min(earliestInputTimestamp, inputCreationTimestamp);
    return this;
  }

  public ShuffleMapInput[] inputBatch() {
    return inputBatch.toArray(new ShuffleMapInput[0]);
  }

  public MapOutputId[] mapOutputIds() {
    return inputBatch.stream().map(ShuffleMapInput::mapOutputId).toArray(MapOutputId[]::new);
  }

  public long totalDataSizeInBytes() {
    return totalDataSizeInBytes;
  }

  public long earliestInputTimestamp() {
    return earliestInputTimestamp;
  }
}
