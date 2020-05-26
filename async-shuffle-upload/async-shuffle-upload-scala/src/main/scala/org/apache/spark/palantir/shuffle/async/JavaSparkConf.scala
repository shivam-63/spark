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

import org.apache.spark.SparkConf
import org.apache.spark.internal.config.ConfigEntry

/**
 * A wrapper around  {@link SparkConf} that allows access to getting configurations with stronogly
 * typed fields that are backed by ConfigEntrys.
 * <p>
 * As the name would suggest, this is primarily intended for use in Java code.
 */
case class JavaSparkConf(conf: SparkConf) {

  def getInt(key: ConfigEntry[Int]): Int = conf.get(key)

  def getLong(key: ConfigEntry[Long]): Long = conf.get(key)

  def getBoolean(key: ConfigEntry[Boolean]): Boolean = conf.get(key)

  def get[T](key: ConfigEntry[T]): T = conf.get(key)
}
