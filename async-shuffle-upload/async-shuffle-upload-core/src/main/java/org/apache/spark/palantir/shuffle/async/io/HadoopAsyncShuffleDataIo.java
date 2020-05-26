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

package org.apache.spark.palantir.shuffle.async.io;

import java.util.function.Supplier;

import org.apache.spark.SparkConf;
import org.apache.spark.palantir.shuffle.async.metadata.ShuffleStorageStateTracker;
import org.apache.spark.palantir.shuffle.async.util.Suppliers;
import org.apache.spark.shuffle.api.ShuffleDataIO;
import org.apache.spark.shuffle.api.ShuffleDriverComponents;
import org.apache.spark.shuffle.api.ShuffleExecutorComponents;
import org.apache.spark.shuffle.sort.io.LocalDiskShuffleDataIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root of the plugin tree, that implements shuffle plugins proposed in SPARK-25299 via
 * asynchronously backing up data to a remote storage system.
 * <p>
 * Throughout the plugin tree, we delegate operations to the local disk implementation. This makes
 * it such that we don't have to re-invent the local disk work from scratch.
 */
public final class HadoopAsyncShuffleDataIo implements ShuffleDataIO {
  private static final Logger log = LoggerFactory.getLogger(HadoopAsyncShuffleDataIo.class);

  private final SparkConf sparkConf;
  private final LocalDiskShuffleDataIO delegate;
  private final Supplier<ShuffleDriverComponents> shuffleDriverComponentsSupplier;

  public HadoopAsyncShuffleDataIo(SparkConf sparkConf) {
    this.sparkConf = sparkConf;
    this.delegate = new LocalDiskShuffleDataIO(sparkConf);
    // HACK: this is a workaround until spark changes merge so that we only call
    // shuffleDataIO.driver() in a single place
    this.shuffleDriverComponentsSupplier = Suppliers.memoize(() ->
        new HadoopAsyncShuffleDriverComponents(
            delegate.driver(), new ShuffleStorageStateTracker()));
  }

  @Override
  public ShuffleDriverComponents driver() {
    log.info("Initializing shuffle driver");
    return shuffleDriverComponentsSupplier.get();
  }

  @Override
  public ShuffleExecutorComponents executor() {
    log.info("Initializing shuffle executor");
    return new HadoopAsyncShuffleExecutorComponents(sparkConf, delegate.executor());
  }
}
