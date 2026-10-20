package com.tcn.exile.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.tcn.exile.ExileClient;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.service.ConfigService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CertificateRotatorTest {

  // Throwaway self-signed cert (fixture only). Its DER SHA-256, bare lowercase hex,
  // computed independently with: openssl x509 -outform DER | openssl dgst -sha256
  private static final String CERT_PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIDGTCCAgGgAwIBAgIUc9uSMbSntZBAsyg5pRLgVNGcleQwDQYJKoZIhvcNAQEL
      BQAwHDEaMBgGA1UEAwwRc2F0aS1yb3RhdG9yLXRlc3QwHhcNMjYwNjE5MTcyODE2
      WhcNMzYwNjE2MTcyODE2WjAcMRowGAYDVQQDDBFzYXRpLXJvdGF0b3ItdGVzdDCC
      ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANlIuQDw6JlJl8QEpGQ+GX24
      kOG6wkyu0cv4meBDWfKPjhTm9J2t++new037E0JvaVqDOxZKYRpwd+/z6BxKzCuC
      aDt8mfSyYn0q4DmgJpiCT/K4eLoqJFn0XCd7nTNGrxRIL6ii90TFpjsEYInYTUf3
      LTnTTSwWQK9XYILRCyCdC5uSQ/TRmKmIWxKMtpK8DJmM2eZBFjUuRrH6ktbMZ+k1
      O/u2UcKMcXSvAJuldze58KQ0Lna3e/LUBIilgt3AhSZbtmZ4gC67gfvV0xSTDauV
      brYzMKGfvaGco3g+5wUH1ZSC8nHcHrkrflJB13T+LlWBkc0H+G0RYyZEd1qq7PMC
      AwEAAaNTMFEwHQYDVR0OBBYEFG3kd+ctzUcQFKVc1wpxxzeRG4gbMB8GA1UdIwQY
      MBaAFG3kd+ctzUcQFKVc1wpxxzeRG4gbMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZI
      hvcNAQELBQADggEBAEjfHiykLf5DnadvzRB7Oui/IqUckypHzJ5k2uXoCFQ0MY8g
      bW87tKSE+CBExz79VJf3R3sBmQd+NDqpx1EVkVhq+h8fmxIwlJVdEJWQAklloehp
      k+tRrOzl6q2aOsNgcnG/tEKoIHXrIJMcaNTfopBF0tvNdUdaE6IHqzogJtm1A77R
      pD3uyDb8ehaYn5EpPSwhy4QY0ZLOfJG76BNY3peEmBG0PGd4psgqQ8ji3EzCiJMl
      rb35lb/edIou1s1ewenKEon5I3IjRqs0pBdCRt8NrWdctawB+mlcM2V+gl4JFItb
      E4S+dzkgmjupgbQrQknu+gwd6ngjdMhQf+gRDfo=
      -----END CERTIFICATE-----
      """;

  // Fixture only: notAfter is 2021, so it is permanently expired.
  private static final String EXPIRED_CERT_PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIDRTCCAi2gAwIBAgIUet6kNdxSyBwPVVDLEWSTrmWKNsowDQYJKoZIhvcNAQEL
      BQAwMjEdMBsGA1UEAwwUc2F0aS1yb3RhdG9yLWV4cGlyZWQxETAPBgNVBAoMCHRl
      c3Qtb3JnMB4XDTIwMDEwMTAwMDAwMFoXDTIxMDEwMTAwMDAwMFowMjEdMBsGA1UE
      AwwUc2F0aS1yb3RhdG9yLWV4cGlyZWQxETAPBgNVBAoMCHRlc3Qtb3JnMIIBIjAN
      BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAomu0yRE94om8lVIwtSfLNP989S7Z
      bymkvkOTXeQewLUnAtviNv4p6qiGmOXWRpYyuEHYuIPavETwWWZU+25XzKAaC7bV
      Vx4VtfapSr8SeUciO/5BO+vZ2BLxC6E+S43Qx382OROMrqus5k2PzbbCXDZvqUBr
      6MqfofQcnpPkqRoCFFmt/PtAOvqQvLsnICsn3IV2HkhBRTWsOM8r0VHDUv4+Lxdr
      0VG7GCsOOsDOPw1NKt14Nl7n1D3YlySeYtP59HTfk4P+2CgyPRGl9ELGd0CQhEqk
      NAQrUo936Tz3Pc4WcQcsfDfa2p0fNI3//G8W9rNwOI4S21qTo1q+ES7btwIDAQAB
      o1MwUTAdBgNVHQ4EFgQUuMDZic4/u2GJq0fy8pUUr8y1g7UwHwYDVR0jBBgwFoAU
      uMDZic4/u2GJq0fy8pUUr8y1g7UwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0B
      AQsFAAOCAQEAG0Zn6QLwkra5ocARm8FQLcC22F+G8P7VJ0aXBqUJPQ8dyEa1Lyoh
      S8im6zIPhFVzT6+YnuR4s4ZlteGXTxdrdp71s9BivMq3Qf9S2nKzx/SthJx8jPgD
      AN4To2i46/OSYNbQEym3MrrERUHeYOaRBGBuZxlZl7fx8p1jp4A6851XNWtQsdfj
      9YGlvlnvYY8zqQ82ndqMo4rYUb64A1rM7zoPU11JbS+wGmq0BV5jaJVjb/cYVE7N
      jXHjp3HG/V/6vVde44P1gvlYkx5WknMqZ5eIEEE5qsO1kYC6Gnyev6BAIudYJqTF
      71MlLilBmuPRBFdm/1xn52HIuNG5VS97EA==
      -----END CERTIFICATE-----
      """;

  private static final String EXPECTED_FINGERPRINT =
      "c8ff21923021e5f5dfeccc2c86ae7777ebdc61e1ace75ecfa1ceb40b5df74cdb";

  // Wide enough that the 2036 fixture falls inside the renewal window.
  private static final int ALWAYS_DUE = 100_000;

  private static ExileConfig configWith(String cert) {
    return ExileConfig.builder()
        .rootCert("root")
        .publicCert(cert)
        .privateKey("key")
        .apiHostname("gate.example.com")
        .build();
  }

  /** A valid Base64 config payload, as the gate returns from RotateCertificate. */
  private static String encodedConfig(String cert) {
    var json =
        "{\"ca_certificate\": \"root\", \"certificate\": \""
            + cert.replace("\n", "\\n")
            + "\", \"private_key\": \"key\", \"api_endpoint\": \"gate.example.com:443\"}";
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  /** Manager wired to a client holding {@code cert}, with a writable watcher by default. */
  private static Harness harness(String cert) throws IOException {
    var manager = mock(ExileClientManager.class);
    var client = mock(ExileClient.class);
    var configService = mock(ConfigService.class);
    var watcher = mock(ConfigFileWatcher.class);

    when(manager.client()).thenReturn(client);
    when(manager.configWatcher()).thenReturn(watcher);
    when(client.config()).thenReturn(configWith(cert));
    when(client.config_()).thenReturn(configService);
    when(watcher.configFile())
        .thenReturn(Path.of("/workdir/config/com.tcn.exiles.sati.config.cfg"));
    return new Harness(manager, client, configService, watcher);
  }

  private record Harness(
      ExileClientManager manager,
      ExileClient client,
      ConfigService configService,
      ConfigFileWatcher watcher) {}

  @Test
  void fingerprintIsBareLowercaseHex() {
    // Must equal hex(sha256(DER)) exactly — the gate's cert lookup key. No colons,
    // no uppercase: a mismatch makes RotateCertificate return "exile certificate not found".
    assertEquals(EXPECTED_FINGERPRINT, CertificateRotator.getCertFingerprint(configWith(CERT_PEM)));
  }

  @Test
  void fingerprintHasNoDelimiters() {
    var fp = CertificateRotator.getCertFingerprint(configWith(CERT_PEM));
    assertFalse(fp.contains(":"), "fingerprint must not be colon-delimited");
    assertEquals(64, fp.length(), "SHA-256 bare hex is 64 chars");
  }

  @Test
  void emptyFingerprintForGarbageCert() {
    assertEquals("", CertificateRotator.getCertFingerprint(configWith("not a cert")));
  }

  @Test
  void fingerprintPrefersTheValueShippedInTheConfig() {
    // fingerprint_sha256_string is the gate's own key; trust it over a local digest.
    var config =
        ExileConfig.builder()
            .rootCert("root")
            .publicCert(CERT_PEM)
            .privateKey("key")
            .apiHostname("gate.example.com")
            .certificateFingerprint(EXPECTED_FINGERPRINT.toUpperCase())
            .build();
    assertEquals(EXPECTED_FINGERPRINT, CertificateRotator.getCertFingerprint(config));
  }

  @Test
  void fingerprintFallsBackToComputedWhenConfigOmitsIt() {
    assertEquals(
        CertificateRotator.computeCertFingerprint(configWith(CERT_PEM)),
        CertificateRotator.getCertFingerprint(configWith(CERT_PEM)));
  }

  @Test
  void rotatesAndWritesTheNewConfig() throws Exception {
    var h = harness(CERT_PEM);
    var newConfig = encodedConfig(CERT_PEM);
    when(h.configService().rotateCertificate(anyString())).thenReturn(newConfig);

    assertTrue(new CertificateRotator(h.manager(), ALWAYS_DUE).checkAndRotate());
    verify(h.configService()).rotateCertificate(EXPECTED_FINGERPRINT);
    verify(h.watcher()).writeConfig(newConfig);
  }

  @Test
  void doesNotRotateTwiceForTheSameCertificate() throws Exception {
    // The loop this guards: the gate issues a cert, the write fails, the client keeps the
    // old cert, and the next tick sends the identical hash again.
    var h = harness(CERT_PEM);
    when(h.configService().rotateCertificate(anyString())).thenReturn(encodedConfig(CERT_PEM));
    doThrow(new IOException("Read-only file system")).when(h.watcher()).writeConfig(anyString());

    var rotator = new CertificateRotator(h.manager(), ALWAYS_DUE);
    assertFalse(rotator.checkAndRotate());
    assertFalse(rotator.checkAndRotate());
    assertFalse(rotator.checkAndRotate());

    verify(h.configService(), times(1)).rotateCertificate(anyString());
  }

  @Test
  void doesNotRotateWhenTheConfigFileIsNotWritable() throws Exception {
    // Issuing is irreversible, so an unwritable config must fail before the gate call.
    var h = harness(CERT_PEM);
    doThrow(new IOException("/workdir/config is not writable")).when(h.watcher()).assertWritable();

    assertFalse(new CertificateRotator(h.manager(), ALWAYS_DUE).checkAndRotate());
    verify(h.configService(), never()).rotateCertificate(anyString());
  }

  @Test
  void doesNotWriteAnUnparseableRotationResponse() throws Exception {
    var h = harness(CERT_PEM);
    when(h.configService().rotateCertificate(anyString())).thenReturn("}{ not a config");

    assertFalse(new CertificateRotator(h.manager(), ALWAYS_DUE).checkAndRotate());
    verify(h.watcher(), never()).writeConfig(anyString());
  }

  @Test
  void doesNotWriteAnEmptyRotationResponse() throws Exception {
    var h = harness(CERT_PEM);
    when(h.configService().rotateCertificate(anyString())).thenReturn("");

    assertFalse(new CertificateRotator(h.manager(), ALWAYS_DUE).checkAndRotate());
    verify(h.watcher(), never()).writeConfig(anyString());
  }

  @Test
  void noRotationOutsideTheRenewalWindow() throws Exception {
    var h = harness(CERT_PEM);

    assertFalse(new CertificateRotator(h.manager(), 1).checkAndRotate());
    verify(h.configService(), never()).rotateCertificate(anyString());
    verify(h.watcher(), never()).assertWritable();
  }

  @Test
  void expiredCertificateDropsTheClientButKeepsTheWatcher() throws Exception {
    // stop() would close the watcher too, so a replacement config could never be seen.
    var h = harness(EXPIRED_CERT_PEM);

    assertFalse(new CertificateRotator(h.manager(), ALWAYS_DUE).checkAndRotate());
    verify(h.manager()).dropClient();
    verify(h.manager(), never()).stop();
    verify(h.configService(), never()).rotateCertificate(anyString());
  }

  @Test
  void noClientMeansNoRotation() {
    var manager = mock(ExileClientManager.class);
    when(manager.client()).thenReturn(null);

    assertFalse(new CertificateRotator(manager, ALWAYS_DUE).checkAndRotate());
    verify(manager, never()).dropClient();
  }

  @Test
  void unparseableCertificateIsNotRotated() throws Exception {
    var h = harness("not a cert");

    assertFalse(new CertificateRotator(h.manager(), ALWAYS_DUE).checkAndRotate());
    verify(h.configService(), never()).rotateCertificate(any());
  }
}
