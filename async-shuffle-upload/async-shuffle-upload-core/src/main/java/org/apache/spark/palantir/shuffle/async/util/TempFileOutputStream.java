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

package org.apache.spark.palantir.shuffle.async.util;

import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.apache.commons.io.FileUtils;

import org.apache.spark.util.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output stream that creates a temporary file, and then atomically renames the temporary  file
 * to the final file when {@link #close()} is called.
 * <p>
 * If {@link #abort(Exception)} is called, the temporary file is deleted and the final file is
 * never created.
 */
public final class TempFileOutputStream extends AbortableOutputStream {

  private static final Logger LOGGER = LoggerFactory.getLogger(TempFileOutputStream.class);

  private final File tempFile;
  private final File outputFile;

  private boolean isClosed;

  public static TempFileOutputStream createTempFile(File outputFile) throws IOException {
    File tempFile = Utils.tempFileWith(outputFile);
    try {
      return new TempFileOutputStream(tempFile, outputFile);
    } catch (IOException e) {
      FileUtils.deleteQuietly(tempFile);
      throw e;
    }
  }

  private TempFileOutputStream(File tempFile, File outputFile) throws IOException {
    super(Files.newOutputStream(tempFile.toPath(), StandardOpenOption.CREATE_NEW));
    this.tempFile = tempFile;
    this.outputFile = outputFile;
    this.isClosed = false;
  }

  @Override
  public void close() throws IOException {
    if (!isClosed) {
      super.close();
      Preconditions.checkState(tempFile.isFile(), "Temporary file was not created.");
      try {
        Files.move(tempFile.toPath(), outputFile.toPath());
      } catch (FileAlreadyExistsException e) {
        LOGGER.warn("Map output file was already created; perhaps another thread"
                + " accidentally downloaded the same data.",
            SafeArg.of("mapOutputFilePath", outputFile.getAbsolutePath()),
            e);
      }
      isClosed = true;
    }
  }

  @Override
  public void abort(Exception cause) {
    try {
      if (!isClosed) {
        super.close();
        if (tempFile.isFile()) {
          Files.delete(tempFile.toPath());
        }
        isClosed = true;
      }
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }
}
