package com.tcn.exile.handler;

import com.tcn.exile.model.DataRecord;

/** {@link #send} blocks while the stream is busy and throws if it closed; let that propagate. */
@FunctionalInterface
public interface RecordSink {
  void send(DataRecord record) throws Exception;
}
