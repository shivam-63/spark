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

import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;

import org.apache.spark.palantir.shuffle.async.AsyncShuffleDataIoSparkConfigs;
import org.apache.spark.palantir.shuffle.async.client.BaseHadoopShuffleClientConfiguration;
import org.apache.spark.palantir.shuffle.async.client.ShuffleStorageStrategy;
import org.apache.spark.palantir.shuffle.async.immutables.ImmutablesStyle;

import org.immutables.value.Value;

/**
 * Extractor for properties that are specific to the merging shuffle storage strategy.
 */
@Value.Immutable
@ImmutablesStyle
public interface MergingHadoopShuffleClientConfiguration {

  BaseHadoopShuffleClientConfiguration baseConfig();

  @Value.Check
  default void check() {
    Preconditions.checkArgument(
        baseConfig().storageStrategy() == ShuffleStorageStrategy.MERGING,
        "Should only be using this configuration for the merging storage strategy.",
        SafeArg.of("incompatibleStrategy", baseConfig().storageStrategy()));
  }

  @Value.Derived
  default long maxBatchSizeBytes() {
    return baseConfig().javaSparkConf().getLong(AsyncShuffleDataIoSparkConfigs.MERGED_BATCH_SIZE());
  }

  @Value.Derived
  default long maxBatchAgeMillis() {
    return baseConfig().javaSparkConf().getLong(
        AsyncShuffleDataIoSparkConfigs.MERGED_BATCH_MAXIMUM_BUFFERED_AGE());
  }

  @Value.Derived
  default int maxBufferedInputs() {
    return baseConfig().javaSparkConf().getInt(
        AsyncShuffleDataIoSparkConfigs.MERGING_MAX_BUFFERED_INPUTS());
  }

  @Value.Derived
  default long pollingIntervalMillis() {
    return baseConfig().javaSparkConf().getLong(
        AsyncShuffleDataIoSparkConfigs.UPLOAD_POLLING_PERIOD());
  }

  @Value.Derived
  default int readLocalDiskParallelism() {
    return baseConfig().javaSparkConf().getInt(
        AsyncShuffleDataIoSparkConfigs.READ_LOCAL_DISK_PARALLELISM());
  }

  static MergingHadoopShuffleClientConfiguration of(
      BaseHadoopShuffleClientConfiguration baseConfig) {
    return builder().baseConfig(baseConfig).build();
  }

  static ImmutableMergingHadoopShuffleClientConfiguration.Builder builder() {
    return ImmutableMergingHadoopShuffleClientConfiguration.builder();
  }
}
