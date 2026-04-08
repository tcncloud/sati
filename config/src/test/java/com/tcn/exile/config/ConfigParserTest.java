package com.tcn.exile.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ConfigParserTest {

  private static final String VALID_JSON =
      """
      {
        "ca_certificate": "-----BEGIN CERTIFICATE-----\\nROOT\\n-----END CERTIFICATE-----",
        "certificate": "-----BEGIN CERTIFICATE-----\\nPUBLIC\\n-----END CERTIFICATE-----",
        "private_key": "-----BEGIN PRIVATE KEY-----\\nKEY\\n-----END PRIVATE KEY-----",
        "api_endpoint": "gate.example.com:8443"
      }
      """;

  @Test
  void parsesBase64EncodedJson() {
    var base64 = Base64.getEncoder().encode(VALID_JSON.getBytes(StandardCharsets.UTF_8));
    var result = ConfigParser.parse(base64);
    assertTrue(result.isPresent());
    var config = result.get();
    assertTrue(config.rootCert().contains("ROOT"));
    assertTrue(config.publicCert().contains("PUBLIC"));
    assertTrue(config.privateKey().contains("KEY"));
    assertEquals("gate.example.com", config.apiHostname());
    assertEquals(8443, config.apiPort());
  }

  @Test
  void parsesRawJson() {
    var raw = VALID_JSON.getBytes(StandardCharsets.UTF_8);
    var result = ConfigParser.parse(raw);
    assertTrue(result.isPresent());
    assertEquals("gate.example.com", result.get().apiHostname());
  }

  @Test
  void parsesJsonWithoutPort() {
    var json =
        """
        {
          "ca_certificate": "root",
          "certificate": "pub",
          "private_key": "key",
          "api_endpoint": "gate.example.com"
        }
        """;
    var result = ConfigParser.parse(json.getBytes(StandardCharsets.UTF_8));
    assertTrue(result.isPresent());
    assertEquals("gate.example.com", result.get().apiHostname());
    assertEquals(443, result.get().apiPort());
  }

  @Test
  void parsesJsonWithoutEndpoint() {
    var json =
        """
        {
          "ca_certificate": "root",
          "certificate": "pub",
          "private_key": "key"
        }
        """;
    var result = ConfigParser.parse(json.getBytes(StandardCharsets.UTF_8));
    // Should fail because apiHostname is required in ExileConfig
    // but the JSON itself is valid
    assertFalse(result.isPresent());
  }

  @Test
  void rejectsMissingCertFields() {
    var json =
        """
        {"api_endpoint": "gate.example.com:443"}
        """;
    var result = ConfigParser.parse(json.getBytes(StandardCharsets.UTF_8));
    assertFalse(result.isPresent());
  }

  @Test
  void rejectsGarbage() {
    var result = ConfigParser.parse("not-json-or-base64".getBytes(StandardCharsets.UTF_8));
    assertFalse(result.isPresent());
  }

  @Test
  void rejectsEmptyInput() {
    var result = ConfigParser.parse(new byte[0]);
    assertFalse(result.isPresent());
  }

  @Test
  void handlesBase64WithTrailingNewline() {
    var base64 =
        Base64.getEncoder().encodeToString(VALID_JSON.getBytes(StandardCharsets.UTF_8)) + "\n";
    var result = ConfigParser.parse(base64.getBytes(StandardCharsets.UTF_8));
    assertTrue(result.isPresent());
  }

  @Test
  void handlesEscapedNewlinesInCerts() {
    var json =
        """
        {
          "ca_certificate": "line1\\nline2\\nline3",
          "certificate": "pub",
          "private_key": "key",
          "api_endpoint": "host:443"
        }
        """;
    var result = ConfigParser.parse(json.getBytes(StandardCharsets.UTF_8));
    assertTrue(result.isPresent());
    assertTrue(result.get().rootCert().contains("\n"));
  }
}
