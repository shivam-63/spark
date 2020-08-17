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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;

/**
 * Implementation of {@link SeekableInput} that reads bytes from a file on local disk.
 */
public final class SeekableFileInput implements SeekableInput {

  private final File file;

  public SeekableFileInput(File file) {
    this.file = file;
  }

  @Override
  public InputStream open() throws IOException {
    return Channels.newInputStream(Files.newByteChannel(file.toPath()));
  }

  @Override
  public InputStream seekToAndOpen(long startPosition, long _len) throws IOException {
    return seekToAndOpen(startPosition);
  }

  @Override
  public InputStream seekToAndOpen(long startPosition) throws IOException {
    SeekableByteChannel channel = Files.newByteChannel(file.toPath());
    channel.position(startPosition);
    return Channels.newInputStream(channel);
  }
}
