package com.tcn.exile.web.handler;

import com.tcn.exile.ExileConfig;
import com.tcn.exile.StreamStatus;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure Java handler for admin endpoints shared across plugins. No framework dependencies.
 *
 * <p>Covers: logs, grpc-status, plugin-status, config, diagnostics, stream status conversion, and
 * certificate expiration. Plugin-specific endpoints (SSE streams, template views, restart
 * strategies) remain in each plugin's controller.
 */
public class AdminHandler {

  private static final Logger log = LoggerFactory.getLogger(AdminHandler.class);
  private static final Pattern LOG_LEVEL = Pattern.compile("\\b(DEBUG|ERROR|INFO|WARN|TRACE)\\b");

  private final ExileClientManager clientManager;

  public AdminHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  // ==================== Logs ====================

  public List<String> listLogs() {
    if (MemoryAppenderInstance.getInstance() == null) {
      return new ArrayList<>();
    }
    return MemoryAppenderInstance.getInstance().getEventsAsList();
  }

  /**
   * Escapes HTML and formats a log line: newlines to {@code <br>}, colors for
   * DEBUG/ERROR/INFO/WARN/TRACE.
   */
  public static String formatLogLineForHtml(String line) {
    if (line == null) return "";
    String s =
        line.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("\n", "<br>");
    return LOG_LEVEL
        .matcher(s)
        .replaceAll(
            m -> "<span class=\"log-" + m.group(1).toLowerCase() + "\">" + m.group(1) + "</span>");
  }

  /** Format all log lines for HTML rendering. */
  public List<String> getFormattedLogs() {
    List<String> raw = listLogs();
    List<String> formatted = new ArrayList<>(raw.size());
    for (String line : raw) {
      formatted.add(formatLogLineForHtml(line));
    }
    return formatted;
  }

  // ==================== gRPC / Stream status ====================

  /** Returns a JSON-friendly map of stream status, or a "not connected" message. */
  public Map<String, Object> grpcStatus() {
    var status = clientManager.streamStatus();
    if (status == null) {
      return Map.of("status", "not connected");
    }
    return streamStatusToMap(status);
  }

  /** Builds a model map for the gRPC status view template. */
  public Map<String, Object> grpcStatusViewModel() {
    var model = new HashMap<String, Object>();
    var client = clientManager.client();
    var status = clientManager.streamStatus();
    model.put("running", status != null && status.isHealthy());
    model.put("configured", client != null);
    if (client != null) {
      var config = client.config();
      model.put("api_endpoint", config.apiHostname() + ":" + config.apiPort());
      model.put("org", config.org());
      model.put("certificate_name", config.certificateName());
      model.put("expiration_date", getCertExpiration(config));

      var lastConfig = client.lastPolledConfig();
      model.put("config_poll_status", lastConfig != null ? "received" : "waiting");
      if (lastConfig != null) {
        model.put("config_name", lastConfig.configName());
      }
    }
    if (status != null) {
      model.put("stream_phase", status.phase().name());
      model.put("last_error", status.lastError() != null ? status.lastError() : "none");
    } else {
      model.put("stream_phase", "N/A");
      model.put("last_error", "none");
    }
    return model;
  }

  /** Builds a model map for the queue status view template. */
  public Map<String, Object> queueStatusViewModel() {
    var status = clientManager.streamStatus();
    var model = new HashMap<String, Object>();
    model.put("running", status != null && status.isHealthy());
    if (status != null) {
      model.put("streamPhase", status.phase().name());
      model.put("queueActiveCount", status.inflight());
      model.put("queueCompletedJobs", status.completedTotal());
      model.put("queueFailedJobs", status.failedTotal());
      model.put("reconnectAttempts", status.reconnectAttempts());
      model.put(
          "connectedSince",
          status.connectedSince() != null ? status.connectedSince().toString() : null);
      model.put(
          "lastDisconnect",
          status.lastDisconnect() != null ? status.lastDisconnect().toString() : null);
    } else {
      model.put("streamPhase", "N/A");
      model.put("queueActiveCount", 0);
      model.put("queueCompletedJobs", 0);
      model.put("queueFailedJobs", 0);
      model.put("reconnectAttempts", 0);
    }
    return model;
  }

  // ==================== Plugin status ====================

  /**
   * Basic plugin status: name and whether the stream is healthy.
   *
   * @param pluginName human-readable plugin name (e.g., "capone", "finvi")
   */
  public Map<String, Object> pluginStatus(String pluginName) {
    var client = clientManager.client();
    if (client == null) {
      return Map.of("name", pluginName, "running", false);
    }
    return Map.of("name", pluginName, "running", client.streamStatus().isHealthy());
  }

  // ==================== Config ====================

  /** Returns the current configuration status. */
  public Map<String, Object> getConfig() {
    var client = clientManager.client();
    if (client == null) {
      return Map.of("status", "not configured");
    }
    var lastConfig = client.lastPolledConfig();
    if (lastConfig == null) {
      return Map.of("status", "waiting for config");
    }
    return Map.of(
        "status", "configured",
        "orgId", lastConfig.orgId(),
        "configName", lastConfig.configName());
  }

  // ==================== Diagnostics ====================

  public Map<String, Object> getDiagnostics() {
    var client = clientManager.client();
    var map = new HashMap<String, Object>();
    map.put("status", client != null ? "connected" : "not connected");
    if (client != null) {
      map.put("streamStatus", streamStatusToMap(client.streamStatus()));
    }
    return map;
  }

  // ==================== Gate client status (legacy compat) ====================

  public Map<String, Object> gateClientJobStreamStatus() {
    var client = clientManager.client();
    if (client == null) {
      return Map.of("state", "STOPPED");
    }
    return client.streamStatus().isHealthy()
        ? Map.of("state", "RUNNING")
        : Map.of("state", "STOPPED");
  }

  public Map<String, Object> gateClientResponseStreamStatus() {
    var client = clientManager.client();
    if (client == null) {
      return Map.of("state", "STOPPED");
    }
    return client.streamStatus().isHealthy()
        ? Map.of("state", "RUNNING")
        : Map.of("state", "STOPPED");
  }

  // ==================== Utilities ====================

  /** Convert a StreamStatus to a Map for JSON serialization. */
  public static Map<String, Object> streamStatusToMap(StreamStatus status) {
    var map = new HashMap<String, Object>();
    map.put("phase", status.phase().name());
    map.put("healthy", status.isHealthy());
    map.put("clientId", status.clientId());
    map.put(
        "connectedSince",
        status.connectedSince() != null ? status.connectedSince().toString() : null);
    map.put(
        "lastDisconnect",
        status.lastDisconnect() != null ? status.lastDisconnect().toString() : null);
    map.put("lastError", status.lastError());
    map.put("inflight", status.inflight());
    map.put("completedTotal", status.completedTotal());
    map.put("failedTotal", status.failedTotal());
    map.put("reconnectAttempts", status.reconnectAttempts());
    return map;
  }

  /** Extract the mTLS certificate expiration date from the ExileConfig. */
  public static String getCertExpiration(ExileConfig config) {
    try {
      var cf = java.security.cert.CertificateFactory.getInstance("X.509");
      var cert =
          (java.security.cert.X509Certificate)
              cf.generateCertificate(
                  new java.io.ByteArrayInputStream(
                      config.publicCert().getBytes(StandardCharsets.UTF_8)));
      return cert.getNotAfter().toInstant().toString();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
