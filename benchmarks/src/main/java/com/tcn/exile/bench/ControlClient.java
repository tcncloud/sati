package com.tcn.exile.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP client for the Go benchmark server's control endpoints. */
final class ControlClient {
  private final HttpClient http;
  private final String base;

  ControlClient(String baseUrl) {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  void reset() throws IOException, InterruptedException {
    post("/reset");
  }

  void setEventRate(int rps) throws IOException, InterruptedException {
    post("/set-event-rate?rps=" + rps);
  }

  /** Returns dispatched count from server. Blocks until request completes. */
  int injectJobs(String type, int count) throws IOException, InterruptedException {
    var body = post("/inject-job?type=" + type + "&count=" + count);
    // Response: "ok: dispatched=N"
    int idx = body.indexOf('=');
    return idx >= 0 ? Integer.parseInt(body.substring(idx + 1).trim()) : 0;
  }

  /** Waits until server /health returns 200, up to the given timeout. */
  boolean waitHealthy(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        var resp =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) return true;
      } catch (Exception ignored) {
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /** Returns a naive JSON parse as a flat Map of the /stats endpoint. */
  Map<String, Object> stats() throws IOException, InterruptedException {
    var body = get("/stats");
    return parseFlatJson(body);
  }

  private String get(String path) throws IOException, InterruptedException {
    var resp =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 300) {
      throw new IOException("GET " + path + " -> " + resp.statusCode() + ": " + resp.body());
    }
    return resp.body();
  }

  private String post(String path) throws IOException, InterruptedException {
    var resp =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 300) {
      throw new IOException("POST " + path + " -> " + resp.statusCode() + ": " + resp.body());
    }
    return resp.body();
  }

  /**
   * Extremely naive JSON parser that handles only flat objects with number and string values — the
   * /stats response shape. Avoids pulling a JSON library for a single call site.
   */
  static Map<String, Object> parseFlatJson(String s) {
    Map<String, Object> out = new LinkedHashMap<>();
    int i = 0;
    while (i < s.length() && s.charAt(i) != '{') i++;
    if (i >= s.length()) return out;
    i++; // skip {
    while (i < s.length()) {
      // skip whitespace + commas
      while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
      if (i >= s.length() || s.charAt(i) == '}') break;
      if (s.charAt(i) != '"') {
        i++;
        continue;
      }
      int keyStart = ++i;
      while (i < s.length() && s.charAt(i) != '"') i++;
      String key = s.substring(keyStart, i);
      i++; // skip closing "
      while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':')) i++;
      // parse value: string or number (no nested objects expected).
      if (i < s.length() && s.charAt(i) == '"') {
        int valStart = ++i;
        while (i < s.length() && s.charAt(i) != '"') i++;
        out.put(key, s.substring(valStart, i));
        i++;
      } else {
        int valStart = i;
        while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
        String raw = s.substring(valStart, i).trim();
        try {
          out.put(key, Long.parseLong(raw));
        } catch (NumberFormatException nfe) {
          try {
            out.put(key, Double.parseDouble(raw));
          } catch (NumberFormatException nfe2) {
            out.put(key, raw);
          }
        }
      }
    }
    return out;
  }
}
