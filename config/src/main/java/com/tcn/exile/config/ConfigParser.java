package com.tcn.exile.config;

import com.tcn.exile.ExileConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the Base64-encoded JSON config file used by all exile integrations.
 *
 * <p>The file format is a Base64-encoded JSON object with fields: {@code ca_certificate}, {@code
 * certificate}, {@code private_key}, and {@code api_endpoint} (as {@code hostname:port}).
 */
public final class ConfigParser {

  private static final Logger log = LoggerFactory.getLogger(ConfigParser.class);

  private ConfigParser() {}

  /** Parse a config file at the given path. Returns empty if the file can't be parsed. */
  public static Optional<ExileConfig> parse(Path path) {
    try {
      var bytes = Files.readAllBytes(path);
      return parse(bytes);
    } catch (Exception e) {
      log.warn("Failed to read config file {}: {}", path, e.getMessage());
      return Optional.empty();
    }
  }

  /** Parse config from raw bytes (Base64-encoded JSON). */
  public static Optional<ExileConfig> parse(byte[] raw) {
    try {
      // Trim trailing whitespace/newline that some editors add.
      var trimmed = new String(raw, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);

      // Try Base64 decode; if it fails, try as-is (already decoded).
      byte[] json;
      try {
        json = Base64.getDecoder().decode(trimmed);
      } catch (IllegalArgumentException e) {
        json = trimmed;
      }

      var map = parseJson(json);
      if (map == null) return Optional.empty();

      var rootCert = (String) map.get("ca_certificate");
      var publicCert = (String) map.get("certificate");
      var privateKey = (String) map.get("private_key");
      var endpoint = (String) map.get("api_endpoint");

      if (rootCert == null || publicCert == null || privateKey == null) {
        log.warn("Config missing required certificate fields");
        return Optional.empty();
      }

      var builder =
          ExileConfig.builder().rootCert(rootCert).publicCert(publicCert).privateKey(privateKey);

      var certName = (String) map.get("certificate_name");
      if (certName != null) builder.certificateName(certName);

      if (endpoint != null && !endpoint.isEmpty()) {
        // Handle both "host:port" and URL formats like "https://host" or "https://host:port".
        String host = endpoint;
        int port = 443;
        if (host.contains("://")) {
          // Strip scheme (https://, http://).
          host = host.substring(host.indexOf("://") + 3);
        }
        // Strip trailing slashes.
        if (host.endsWith("/")) {
          host = host.substring(0, host.length() - 1);
        }
        // Split host:port.
        int colonIdx = host.lastIndexOf(':');
        if (colonIdx > 0) {
          try {
            port = Integer.parseInt(host.substring(colonIdx + 1));
            host = host.substring(0, colonIdx);
          } catch (NumberFormatException e) {
            // No valid port — use host as-is with default port.
          }
        }
        builder.apiHostname(host);
        builder.apiPort(port);
      }

      return Optional.of(builder.build());
    } catch (Exception e) {
      log.warn("Failed to parse config: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Minimal JSON object parser for the config file. Returns a flat Map of string keys to string
   * values. Avoids pulling in Jackson/Gson as a dependency for this single use case.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseJson(byte[] json) {
    // Use the built-in scripting approach: parse as key-value pairs.
    // The config JSON is simple: {"key": "value", ...} with string values only.
    try {
      var str = new String(json, StandardCharsets.UTF_8).trim();
      if (!str.startsWith("{") || !str.endsWith("}")) return null;
      str = str.substring(1, str.length() - 1);

      var result = new java.util.LinkedHashMap<String, Object>();
      int i = 0;
      while (i < str.length()) {
        // Skip whitespace and commas.
        while (i < str.length()
            && (str.charAt(i) == ' '
                || str.charAt(i) == ','
                || str.charAt(i) == '\n'
                || str.charAt(i) == '\r'
                || str.charAt(i) == '\t')) i++;
        if (i >= str.length()) break;

        // Parse key.
        if (str.charAt(i) != '"') break;
        int keyStart = ++i;
        while (i < str.length() && str.charAt(i) != '"') {
          if (str.charAt(i) == '\\') i++; // skip escaped char
          i++;
        }
        var key = str.substring(keyStart, i);
        i++; // closing quote

        // Skip colon.
        while (i < str.length() && (str.charAt(i) == ' ' || str.charAt(i) == ':')) i++;

        // Parse value.
        if (i >= str.length()) break;
        if (str.charAt(i) == '"') {
          int valStart = ++i;
          var sb = new StringBuilder();
          while (i < str.length() && str.charAt(i) != '"') {
            if (str.charAt(i) == '\\' && i + 1 < str.length()) {
              i++;
              sb.append(
                  switch (str.charAt(i)) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> str.charAt(i);
                  });
            } else {
              sb.append(str.charAt(i));
            }
            i++;
          }
          result.put(key, sb.toString());
          i++; // closing quote
        } else {
          // Non-string value (number, boolean, null) — read until comma/brace.
          int valStart = i;
          while (i < str.length() && str.charAt(i) != ',' && str.charAt(i) != '}') i++;
          result.put(key, str.substring(valStart, i).trim());
        }
      }
      return result;
    } catch (Exception e) {
      log.warn("JSON parse error: {}", e.getMessage());
      return null;
    }
  }
}
