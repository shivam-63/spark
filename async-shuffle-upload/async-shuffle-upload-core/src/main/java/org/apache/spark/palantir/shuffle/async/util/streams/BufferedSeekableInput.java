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

package org.apache.spark.palantir.shuffle.async.util.streams;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Implementation of {@link SeekableInput} that wraps returned streams with a
 * {@link BufferedInputStream}, with a buffer of the specified size.
 */
public final class BufferedSeekableInput implements SeekableInput {

  private final SeekableInput delegate;
  private final int bufferSize;

  public BufferedSeekableInput(SeekableInput delegate, int bufferSize) {
    this.delegate = delegate;
    this.bufferSize = bufferSize;
  }

  @Override
  public InputStream open() throws IOException {
    return new BufferedInputStream(delegate.open(), bufferSize);
  }

  @Override
  public InputStream seekToAndOpen(long startPosition, long len) throws IOException {
    return new BufferedInputStream(delegate.seekToAndOpen(startPosition, len), bufferSize);
  }

  @Override
  public InputStream seekToAndOpen(long startPosition) throws IOException {
    return new BufferedInputStream(delegate.seekToAndOpen(startPosition), bufferSize);
  }
}
