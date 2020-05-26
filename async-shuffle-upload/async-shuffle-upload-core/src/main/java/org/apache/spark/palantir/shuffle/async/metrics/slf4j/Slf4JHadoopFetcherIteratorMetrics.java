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

import com.palantir.logsafe.SafeArg;

import org.apache.spark.palantir.shuffle.async.metrics.HadoopFetcherIteratorMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Slf4JHadoopFetcherIteratorMetrics implements HadoopFetcherIteratorMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(
      Slf4JHadoopFetcherIteratorMetrics.class);

  private final SafeArg<String> sparkAppNameArg;

  public Slf4JHadoopFetcherIteratorMetrics(String sparkAppName) {
    this.sparkAppNameArg = Args.sparkAppNameArg(sparkAppName);
  }

  @Override
  public void markFetchFromRemoteFailed(int shuffleId, int mapId, int reduceId, long attemptId) {
    LOGGER.info("Failed to fetch shuffle blocks from remote storage.",
        sparkAppNameArg,
        Args.shuffleIdArg(shuffleId),
        Args.mapIdArg(mapId),
        Args.reduceIdArg(reduceId),
        Args.attemptIdArg(attemptId));
  }

  @Override
  public void markFetchFromRemoteSucceeded(int shuffleId, int mapId, int reduceId, long attemptId) {
    LOGGER.info("Successfully fetched shuffle blocks from remote storage.",
        sparkAppNameArg,
        Args.shuffleIdArg(shuffleId),
        Args.mapIdArg(mapId),
        Args.reduceIdArg(reduceId),
        Args.attemptIdArg(attemptId));
  }

  @Override
  public void markFetchFromExecutorFailed(int shuffleId, int mapId, int reduceId, long attemptId) {
    LOGGER.info("Failed to fetch shuffle blocks from other executors in the application.",
        sparkAppNameArg,
        Args.shuffleIdArg(shuffleId),
        Args.mapIdArg(mapId),
        Args.reduceIdArg(reduceId),
        Args.attemptIdArg(attemptId));
  }
}
