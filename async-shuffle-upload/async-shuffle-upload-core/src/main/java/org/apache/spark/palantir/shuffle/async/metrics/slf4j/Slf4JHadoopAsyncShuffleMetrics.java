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

package org.apache.spark.palantir.shuffle.async.metrics.slf4j;

import org.apache.spark.palantir.shuffle.async.metrics.BasicShuffleClientMetrics;
import org.apache.spark.palantir.shuffle.async.metrics.MergingShuffleClientMetrics;
import org.apache.spark.palantir.shuffle.async.metrics.HadoopAsyncShuffleMetrics;
import org.apache.spark.palantir.shuffle.async.metrics.HadoopFetcherIteratorMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Slf4JHadoopAsyncShuffleMetrics implements HadoopAsyncShuffleMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(
      Slf4JHadoopAsyncShuffleMetrics.class);

  private final String sparkAppName;
  private final Slf4jBasicShuffleClientMetrics basicShuffleClientMetrics;
  private final Slf4jMergingShuffleClientMetrics mergingShuffleClientMetrics;
  private final Slf4JHadoopFetcherIteratorMetrics hadoopFetcherIteratorMetrics;

  public Slf4JHadoopAsyncShuffleMetrics(String sparkAppName) {
    this.sparkAppName = sparkAppName;
    this.basicShuffleClientMetrics = new Slf4jBasicShuffleClientMetrics(sparkAppName);
    this.mergingShuffleClientMetrics = new Slf4jMergingShuffleClientMetrics(sparkAppName);
    this.hadoopFetcherIteratorMetrics = new Slf4JHadoopFetcherIteratorMetrics(sparkAppName);
  }

  @Override
  public void markUsingAsyncShuffleUploadPlugin() {
    LOGGER.info("Using the async shuffle upload plugin.", Args.sparkAppNameArg(sparkAppName));
  }

  @Override
  public BasicShuffleClientMetrics basicShuffleClientMetrics() {
    return basicShuffleClientMetrics;
  }

  @Override
  public MergingShuffleClientMetrics mergingShuffleClientMetrics() {
    return mergingShuffleClientMetrics;
  }

  @Override
  public HadoopFetcherIteratorMetrics hadoopFetcherIteratorMetrics() {
    return hadoopFetcherIteratorMetrics;
  }
}
