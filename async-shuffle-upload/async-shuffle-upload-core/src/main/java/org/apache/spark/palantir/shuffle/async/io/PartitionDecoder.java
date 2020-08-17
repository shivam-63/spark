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

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.spark.network.util.LimitedInputStream;
import org.apache.spark.palantir.shuffle.async.util.PartitionOffsets;
import org.apache.spark.palantir.shuffle.async.util.streams.SeekableInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper module for extracting partition index offsets and data blocks from shuffle data
 * files and index files represented by {@link SeekableInput} streams.
 * <p>
 * Recall that shuffle data files are sequences of byte blocks - each byte block has a byte start
 * index and a byte end index. An index file that is the companion of a data file indicates the byte
 * start and end offsets where a partition data block starts and ends.
 */
public final class PartitionDecoder {

  private static final Logger LOGGER = LoggerFactory.getLogger(PartitionDecoder.class);

  public static PartitionOffsets decodePartitionOffsets(SeekableInput index, int partitionId) {
    long dataOffset;
    long nextOffset;
    try (InputStream indexInputStream = index.seekToAndOpen(partitionId * 8L, 16);
         DataInputStream indexDataInputStream = new DataInputStream(indexInputStream)) {
      dataOffset = indexDataInputStream.readLong();
      nextOffset = indexDataInputStream.readLong();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return PartitionOffsets.of(dataOffset, nextOffset);
  }

  public static InputStream decodePartitionData(SeekableInput data, PartitionOffsets offsets) {
    InputStream dataInputStream = null;
    LimitedInputStream limitedDataInputStream = null;
    try {
      dataInputStream = data.seekToAndOpen(offsets.dataOffset(), offsets.length());
      limitedDataInputStream = new LimitedInputStream(dataInputStream, offsets.length());
    } catch (IOException e) {
      closeQuietly(limitedDataInputStream, e);
      closeQuietly(dataInputStream, e);
      throw new RuntimeException(e);
    }
    return limitedDataInputStream;
  }

  public static InputStream decodePartition(
      SeekableInput data, SeekableInput index, int partitionId) {
    PartitionOffsets offsets = decodePartitionOffsets(index, partitionId);
    return decodePartitionData(data, offsets);
  }

  private static void closeQuietly(Closeable closeable, IOException rootException) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        rootException.addSuppressed(e);
        LOGGER.warn("Error encountered when trying to close resource after an error occurred" +
            " trying to read from it.", e);
      }
    }
  }

  private PartitionDecoder() {
  }
}
