/*
 *  (C) 2017-2025 TCN Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tcn.exile.memlogger;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MemoryOutputStream extends OutputStream {
  private static final int MAX_BUFFER_SIZE = 1000;
  private final BlockingQueue<String> events;
  private final StringBuilder currentLine;
  private volatile boolean closed;

  public MemoryOutputStream() {
    this.events = new ArrayBlockingQueue<>(MAX_BUFFER_SIZE);
    this.currentLine = new StringBuilder();
    this.closed = false;
  }

  @Override
  public void write(int b) throws IOException {
    if (closed) {
      throw new IOException("Stream is closed");
    }

    char c = (char) b;
    currentLine.append(c);

    if (c == '\n') {
      String line = currentLine.toString();
      currentLine.setLength(0);

      // If queue is full, remove oldest element
      if (!events.offer(line)) {
        events.poll();
        events.offer(line);
      }
    }
  }

  public String[] getEvents() {
    return events.toArray(new String[0]);
  }

  public void clear() {
    events.clear();
    currentLine.setLength(0);
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      clear();
    }
  }

  public boolean isClosed() {
    return closed;
  }
}
