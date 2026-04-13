package com.tcn.exile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExileConfigTest {

  @Test
  void builderSetsFields() {
    var config =
        ExileConfig.builder()
            .rootCert("root")
            .publicCert("pub")
            .privateKey("key")
            .apiHostname("gate.example.com")
            .apiPort(8443)
            .build();
    assertEquals("root", config.rootCert());
    assertEquals("pub", config.publicCert());
    assertEquals("key", config.privateKey());
    assertEquals("gate.example.com", config.apiHostname());
    assertEquals(8443, config.apiPort());
  }

  @Test
  void defaultPort() {
    var config =
        ExileConfig.builder()
            .rootCert("root")
            .publicCert("pub")
            .privateKey("key")
            .apiHostname("gate.example.com")
            .build();
    assertEquals(443, config.apiPort());
  }

  @Test
  void nullFieldsThrow() {
    assertThrows(NullPointerException.class, () -> ExileConfig.builder().build());
    assertThrows(
        NullPointerException.class,
        () -> ExileConfig.builder().rootCert("r").publicCert("p").build());
  }
}
