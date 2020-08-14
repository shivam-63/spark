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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

public final class SeekableByteArrayInput implements SeekableInput {

  private final byte[] array;

  public SeekableByteArrayInput(byte[] array) {
    this.array = array;
  }

  @Override
  public InputStream open() {
    return new ByteArrayInputStream(array);
  }

  @Override
  public InputStream seekToAndOpen(long startPosition, long _len) throws IOException {
    return seekToAndOpen(startPosition);
  }

  @Override
  public InputStream seekToAndOpen(long startPosition) throws IOException {
    SeekableByteChannel arrayAsChannel = new SeekableInMemoryByteChannel(array);
    arrayAsChannel.position(startPosition);
    return Channels.newInputStream(arrayAsChannel);
  }
}
