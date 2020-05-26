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

package org.apache.spark.palantir.shuffle.async.metrics;

import org.apache.spark.SparkConf;
import org.apache.spark.palantir.shuffle.async.AsyncShuffleDataIoSparkConfigs;
import org.apache.spark.palantir.shuffle.async.api.SparkShuffleApiConstants;

/**
 * Entry point for collecting metrics from shuffle uploads.
 * <p>
 * Implementations are instantiated reflectively with a no-arg constructor. Specify the
 * implementation class via {@link AsyncShuffleDataIoSparkConfigs#METRICS_FACTORY_CLASS()}.
 */
public interface HadoopAsyncShuffleMetricsFactory {

  /**
   * Instantiate a metrics system for measuring the performance of shuffle uploads and downloads.
   *
   * @param sparkConf    The Spark configuration of the application.
   * @param sparkAppName The name of the application. Either the appId, or can be overridden by
   *                     {@link SparkShuffleApiConstants#SHUFFLE_PLUGIN_APP_NAME_CONF} to give a
   *                     custom tag for metrics that are collected by the system.
   */
  HadoopAsyncShuffleMetrics create(SparkConf sparkConf, String sparkAppName);
}
