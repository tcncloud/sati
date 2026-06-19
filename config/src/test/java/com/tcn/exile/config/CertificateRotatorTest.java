package com.tcn.exile.config;

import static org.junit.jupiter.api.Assertions.*;

import com.tcn.exile.ExileConfig;
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

  private static final String EXPECTED_FINGERPRINT =
      "c8ff21923021e5f5dfeccc2c86ae7777ebdc61e1ace75ecfa1ceb40b5df74cdb";

  private static ExileConfig configWith(String cert) {
    return ExileConfig.builder()
        .rootCert("root")
        .publicCert(cert)
        .privateKey("key")
        .apiHostname("gate.example.com")
        .build();
  }

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
}
