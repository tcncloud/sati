package com.tcn.exile.internal;

import build.buf.gen.tcnapi.exile.gate.v3.LogLevel;
import build.buf.gen.tcnapi.exile.gate.v3.LogRecord;
import com.google.protobuf.Timestamp;
import com.tcn.exile.memlogger.LogShipper;
import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.service.TelemetryService;
import io.opentelemetry.api.trace.Span;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LogShipper implementation that sends structured log records to the gate via TelemetryService.
 * All exceptions are caught — telemetry must never break the application.
 */
public final class GrpcLogShipper implements LogShipper {

  private static final Logger log = LoggerFactory.getLogger(GrpcLogShipper.class);

  private final TelemetryService telemetryService;
  private final String clientId;

  public GrpcLogShipper(TelemetryService telemetryService, String clientId) {
    this.telemetryService = telemetryService;
    this.clientId = clientId;
  }

  @Override
  public void shipLogs(List<String> payload) {
    // Legacy string-only path: wrap as INFO-level records.
    var records = new ArrayList<LogRecord>();
    var now = Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build();
    for (var msg : payload) {
      records.add(
          LogRecord.newBuilder()
              .setTime(now)
              .setLevel(LogLevel.LOG_LEVEL_INFO)
              .setMessage(msg)
              .build());
    }
    sendRecords(records);
  }

  @Override
  public void shipStructuredLogs(List<MemoryAppender.LogEvent> events) {
    var records = new ArrayList<LogRecord>();
    for (var event : events) {
      var builder =
          LogRecord.newBuilder()
              .setTime(
                  Timestamp.newBuilder().setSeconds(event.timestamp / 1000).setNanos((int) ((event.timestamp % 1000) * 1_000_000)))
              .setLevel(mapLevel(event.level))
              .setMessage(event.message != null ? event.message : "");

      if (event.loggerName != null) builder.setLoggerName(event.loggerName);
      if (event.threadName != null) builder.setThreadName(event.threadName);
      if (event.stackTrace != null) builder.setStackTrace(event.stackTrace);
      if (event.mdc != null) builder.putAllMdc(event.mdc);

      // Attach trace context from the current span if available.
      var spanContext = Span.current().getSpanContext();
      if (spanContext.isValid()) {
        builder.setTraceId(spanContext.getTraceId());
        builder.setSpanId(spanContext.getSpanId());
      }

      records.add(builder.build());
    }
    sendRecords(records);
  }

  private void sendRecords(List<LogRecord> records) {
    try {
      int accepted = telemetryService.reportLogs(clientId, records);
      log.debug("Shipped {} log records ({} accepted)", records.size(), accepted);
    } catch (Exception e) {
      log.debug("Failed to ship logs: {}", e.getMessage());
    }
  }

  private static LogLevel mapLevel(String level) {
    if (level == null) return LogLevel.LOG_LEVEL_INFO;
    return switch (level.toUpperCase()) {
      case "TRACE" -> LogLevel.LOG_LEVEL_TRACE;
      case "DEBUG" -> LogLevel.LOG_LEVEL_DEBUG;
      case "INFO" -> LogLevel.LOG_LEVEL_INFO;
      case "WARN" -> LogLevel.LOG_LEVEL_WARN;
      case "ERROR" -> LogLevel.LOG_LEVEL_ERROR;
      default -> LogLevel.LOG_LEVEL_INFO;
    };
  }

  @Override
  public void stop() {
    // No-op — channel lifecycle is managed by ExileClient.
  }
}
