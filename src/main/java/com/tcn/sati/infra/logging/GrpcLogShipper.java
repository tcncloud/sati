package com.tcn.sati.infra.logging;

import build.buf.gen.tcnapi.exile.gate.v3.LogLevel;
import build.buf.gen.tcnapi.exile.gate.v3.LogRecord;
import com.google.protobuf.Timestamp;
import com.tcn.sati.infra.gate.GateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LogShipper that converts MemoryLogAppender entries to protobuf LogRecord and
 * ships them to Gate via GateClient.reportLogs(). All exceptions are caught —
 * telemetry must never break the application.
 */
public final class GrpcLogShipper implements MemoryLogAppender.LogShipper {

    private static final Logger log = LoggerFactory.getLogger(GrpcLogShipper.class);

    private final GateClient gateClient;
    private final String clientId;

    public GrpcLogShipper(GateClient gateClient, String clientId) {
        this.gateClient = gateClient;
        this.clientId = clientId;
    }

    @Override
    public void shipStructuredLogs(List<MemoryLogAppender.LogEntry> entries) {
        var records = new ArrayList<LogRecord>(entries.size());
        for (var entry : entries) {
            var builder = LogRecord.newBuilder()
                    .setTime(Timestamp.newBuilder()
                            .setSeconds(entry.timestampMs / 1000)
                            .setNanos((int) ((entry.timestampMs % 1000) * 1_000_000)))
                    .setLevel(mapLevel(entry.level))
                    .setMessage(toJson(entry));

            if (entry.loggerName != null) builder.setLoggerName(entry.loggerName);
            if (entry.threadName != null) builder.setThreadName(entry.threadName);
            if (entry.stackTrace != null) builder.setStackTrace(entry.stackTrace);
            if (entry.mdc != null && !entry.mdc.isEmpty()) builder.putAllMdc(entry.mdc);
            if (entry.traceId != null) builder.setTraceId(entry.traceId);
            if (entry.spanId != null) builder.setSpanId(entry.spanId);

            records.add(builder.build());
        }
        try {
            int accepted = gateClient.reportLogs(clientId, records);
            log.debug("Shipped {} log records ({} accepted)", records.size(), accepted);
        } catch (Exception e) {
            log.debug("Failed to ship logs: {}", e.getMessage());
        }
    }

    private static String toJson(MemoryLogAppender.LogEntry entry) {
        var map = new LinkedHashMap<String, Object>();
        map.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(entry.timestampMs).atOffset(ZoneOffset.UTC)));
        if (entry.level != null) map.put("level", entry.level);
        if (entry.loggerName != null) map.put("logger", entry.loggerName);
        if (entry.rawMessage != null) map.put("message", entry.rawMessage);
        if (entry.threadName != null) map.put("thread", entry.threadName);
        if (entry.mdc != null && !entry.mdc.isEmpty()) map.put("mdc", entry.mdc);
        if (entry.stackTrace != null) map.put("stackTrace", entry.stackTrace);
        if (entry.traceId != null) map.put("traceId", entry.traceId);
        if (entry.spanId != null) map.put("spanId", entry.spanId);
        return toJsonString(map);
    }

    private static String toJsonString(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (obj instanceof Number n) return n.toString();
        if (obj instanceof Boolean b) return b.toString();
        if (obj instanceof Map<?, ?> m) {
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(e.getKey().toString())).append("\":");
                sb.append(toJsonString(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        return "\"" + escapeJson(obj.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static LogLevel mapLevel(String level) {
        if (level == null) return LogLevel.LOG_LEVEL_INFO;
        return switch (level.toUpperCase()) {
            case "TRACE" -> LogLevel.LOG_LEVEL_TRACE;
            case "DEBUG" -> LogLevel.LOG_LEVEL_DEBUG;
            case "INFO"  -> LogLevel.LOG_LEVEL_INFO;
            case "WARN"  -> LogLevel.LOG_LEVEL_WARN;
            case "ERROR" -> LogLevel.LOG_LEVEL_ERROR;
            default      -> LogLevel.LOG_LEVEL_INFO;
        };
    }
}
