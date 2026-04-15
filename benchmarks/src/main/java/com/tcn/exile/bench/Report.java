package com.tcn.exile.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON report writer. Avoids pulling a JSON library for one use site.
 *
 * <p>Emits a single flat object per run; each scenario becomes a nested object.
 */
final class Report {
  private final Map<String, Object> metadata = new LinkedHashMap<>();
  private final Map<String, Scenario> scenarios = new LinkedHashMap<>();

  void putMetadata(String key, Object value) {
    metadata.put(key, value);
  }

  Scenario scenario(String name) {
    return scenarios.computeIfAbsent(name, Scenario::new);
  }

  static final class Scenario {
    final String name;
    double durationSeconds;
    double jobsPerSec;
    double eventsPerSec;
    long[] jobLatencyMsP50P95P99Max;
    long[] eventLatencyMsP50P95P99Max;
    long jobErrors;
    long eventErrors;
    long totalJobs;
    long totalEvents;
    Map<String, Object> serverStats;
    Map<String, Object> adaptive;

    Scenario(String name) {
      this.name = name;
    }
  }

  void writeTo(PrintStream out) {
    out.print(renderJson());
  }

  void writeTo(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    try (Writer w = Files.newBufferedWriter(path)) {
      w.write(renderJson());
    }
  }

  String renderJson() {
    StringBuilder sb = new StringBuilder(1024);
    sb.append("{\n");
    sb.append("  \"metadata\": ");
    writeMap(sb, metadata, 2);
    sb.append(",\n  \"scenarios\": {\n");
    boolean first = true;
    for (var e : scenarios.entrySet()) {
      if (!first) sb.append(",\n");
      first = false;
      writeScenario(sb, e.getKey(), e.getValue(), 4);
    }
    sb.append("\n  }\n}\n");
    return sb.toString();
  }

  private void writeScenario(StringBuilder sb, String name, Scenario s, int indent) {
    String pad = " ".repeat(indent);
    sb.append(pad).append('"').append(esc(name)).append("\": {\n");
    sb.append(pad).append("  \"duration_s\": ").append(fmt(s.durationSeconds)).append(",\n");
    sb.append(pad).append("  \"throughput\": {");
    sb.append("\"jobs_per_s\": ").append(fmt(s.jobsPerSec));
    sb.append(", \"events_per_s\": ").append(fmt(s.eventsPerSec));
    sb.append("},\n");
    sb.append(pad).append("  \"total\": {");
    sb.append("\"jobs\": ").append(s.totalJobs);
    sb.append(", \"events\": ").append(s.totalEvents);
    sb.append("},\n");
    sb.append(pad).append("  \"errors\": {");
    sb.append("\"jobs\": ").append(s.jobErrors);
    sb.append(", \"events\": ").append(s.eventErrors);
    sb.append("},\n");
    sb.append(pad).append("  \"job_latency_ms\": ");
    writePercentiles(sb, s.jobLatencyMsP50P95P99Max);
    sb.append(",\n");
    sb.append(pad).append("  \"event_latency_ms\": ");
    writePercentiles(sb, s.eventLatencyMsP50P95P99Max);
    if (s.serverStats != null) {
      sb.append(",\n").append(pad).append("  \"server_stats\": ");
      writeMap(sb, s.serverStats, indent + 2);
    }
    if (s.adaptive != null) {
      sb.append(",\n").append(pad).append("  \"adaptive\": ");
      writeMap(sb, s.adaptive, indent + 2);
    }
    sb.append("\n").append(pad).append("}");
  }

  private void writePercentiles(StringBuilder sb, long[] p) {
    if (p == null) p = new long[] {0, 0, 0, 0};
    sb.append("{\"p50\": ").append(p[0]);
    sb.append(", \"p95\": ").append(p[1]);
    sb.append(", \"p99\": ").append(p[2]);
    sb.append(", \"max\": ").append(p[3]);
    sb.append("}");
  }

  @SuppressWarnings("unchecked")
  private void writeMap(StringBuilder sb, Map<String, Object> m, int indent) {
    if (m.isEmpty()) {
      sb.append("{}");
      return;
    }
    String pad = " ".repeat(indent);
    sb.append("{\n");
    boolean first = true;
    for (var e : m.entrySet()) {
      if (!first) sb.append(",\n");
      first = false;
      sb.append(pad).append("  \"").append(esc(e.getKey())).append("\": ");
      writeValue(sb, e.getValue(), indent + 2);
    }
    sb.append("\n").append(pad).append("}");
  }

  @SuppressWarnings("unchecked")
  private void writeValue(StringBuilder sb, Object v, int indent) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof Number) {
      sb.append(v);
    } else if (v instanceof Boolean) {
      sb.append(v);
    } else if (v instanceof Map) {
      writeMap(sb, (Map<String, Object>) v, indent);
    } else {
      sb.append('"').append(esc(v.toString())).append('"');
    }
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String fmt(double d) {
    return String.format("%.2f", d);
  }
}
