package com.tcn.exile.demo;

import com.sun.net.httpserver.HttpServer;
import com.tcn.exile.StreamStatus;
import com.tcn.exile.config.ExileClientManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight HTTP server for health checks and status inspection. Uses Java's built-in HttpServer
 * — no framework dependency.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /health} — returns "ok" or "unhealthy"
 *   <li>{@code GET /status} — returns JSON with stream status details
 * </ul>
 */
public class StatusServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(StatusServer.class);

  private final HttpServer server;
  private final ExileClientManager manager;

  public StatusServer(ExileClientManager manager, int port) throws IOException {
    this.manager = manager;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

    server.createContext(
        "/health",
        exchange -> {
          var status = manager.streamStatus();
          boolean healthy = status != null && status.isHealthy();
          int code = healthy ? 200 : 503;
          var body = healthy ? "ok\n" : "unhealthy\n";
          exchange.sendResponseHeaders(code, body.length());
          try (var os = exchange.getResponseBody()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
          }
        });

    server.createContext(
        "/status",
        exchange -> {
          var status = manager.streamStatus();
          var json = formatStatus(status);
          var body = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    server.createContext(
        "/",
        exchange -> {
          var body =
              """
          <!doctype html>
          <html>
          <head><title>sati-demo</title></head>
          <body>
            <h1>sati-demo</h1>
            <ul>
              <li><a href="/health">/health</a> — health check</li>
              <li><a href="/status">/status</a> — stream status (JSON)</li>
            </ul>
          </body>
          </html>
          """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/html");
          exchange.sendResponseHeaders(200, body.length);
          try (var os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
  }

  public void start() {
    server.start();
    log.info("Status server listening on port {}", server.getAddress().getPort());
  }

  @Override
  public void close() {
    server.stop(1);
    log.info("Status server stopped");
  }

  private String formatStatus(StreamStatus status) {
    if (status == null) {
      return """
          {"phase":"NO_CLIENT","healthy":false}""";
    }
    // Simple JSON without any library.
    return String.format(
        """
        {
          "phase": "%s",
          "healthy": %s,
          "client_id": %s,
          "connected_since": %s,
          "last_disconnect": %s,
          "last_error": %s,
          "inflight": %d,
          "completed_total": %d,
          "failed_total": %d,
          "reconnect_attempts": %d
        }""",
        status.phase(),
        status.isHealthy(),
        jsonString(status.clientId()),
        jsonString(status.connectedSince()),
        jsonString(status.lastDisconnect()),
        jsonString(status.lastError()),
        status.inflight(),
        status.completedTotal(),
        status.failedTotal(),
        status.reconnectAttempts());
  }

  private static String jsonString(Object value) {
    if (value == null) return "null";
    return "\"" + value.toString().replace("\"", "\\\"") + "\"";
  }
}
