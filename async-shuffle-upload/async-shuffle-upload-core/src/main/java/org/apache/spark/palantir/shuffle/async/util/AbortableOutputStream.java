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

import java.io.FilterOutputStream;
import java.io.OutputStream;

/**
 * Output stream that may create some temporary resources that should be cleaned up
 * if an error were to occur while the stream is open.
 */
public abstract class AbortableOutputStream extends FilterOutputStream {

  protected AbortableOutputStream(OutputStream out) {
    super(out);
  }

  /**
   * Has distinct semantics from {@link #close()} in that in the case of an error, no state that
   * was created by this output stream should remain.
   */
  public abstract void abort(Exception cause);
}
