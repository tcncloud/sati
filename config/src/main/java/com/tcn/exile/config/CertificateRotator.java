package com.tcn.exile.config;

import com.tcn.exile.ExileConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks certificate expiration and rotates via the gate config service.
 *
 * <p>Intended to be called periodically (e.g., every hour). If the certificate expires within the
 * configured threshold (default 10 days), it calls the gate service to rotate and writes the new
 * certificate to the config file.
 */
public final class CertificateRotator {

  private static final Logger log = LoggerFactory.getLogger(CertificateRotator.class);
  private static final int DEFAULT_RENEWAL_DAYS = 10;

  private final ExileClientManager manager;
  private final int renewalDays;

  public CertificateRotator(ExileClientManager manager) {
    this(manager, DEFAULT_RENEWAL_DAYS);
  }

  public CertificateRotator(ExileClientManager manager, int renewalDays) {
    this.manager = manager;
    this.renewalDays = renewalDays;
  }

  /**
   * Check and rotate if needed. Returns true if rotation was performed.
   *
   * <p>Call this periodically (e.g., hourly).
   */
  public boolean checkAndRotate() {
    var client = manager.client();
    if (client == null) return false;

    var config = client.config();
    Instant expiration = getCertExpiration(config);
    if (expiration == null) {
      log.warn("Could not determine certificate expiration date");
      return false;
    }

    var now = Instant.now();
    if (expiration.isBefore(now)) {
      log.error("Certificate has expired ({}). Manual renewal required.", expiration);
      manager.stop();
      return false;
    }

    if (expiration.isBefore(now.plus(renewalDays, ChronoUnit.DAYS))) {
      log.info("Certificate expires at {}, attempting rotation", expiration);
      try {
        var hash = getCertFingerprint(config);
        var newCert = client.config_().rotateCertificate(hash);
        if (newCert != null && !newCert.isEmpty()) {
          manager.configWatcher().writeConfig(newCert);
          log.info("Certificate rotated successfully");
          return true;
        } else {
          log.warn("Certificate rotation returned empty response");
        }
      } catch (Exception e) {
        log.error("Certificate rotation failed: {}", e.getMessage());
      }
    } else {
      log.debug(
          "Certificate valid until {}, no rotation needed ({} day threshold)",
          expiration,
          renewalDays);
    }
    return false;
  }

  static Instant getCertExpiration(ExileConfig config) {
    try {
      var cf = CertificateFactory.getInstance("X.509");
      var cert =
          (X509Certificate)
              cf.generateCertificate(
                  new ByteArrayInputStream(config.publicCert().getBytes(StandardCharsets.UTF_8)));
      return cert.getNotAfter().toInstant();
    } catch (Exception e) {
      return null;
    }
  }

  static String getCertFingerprint(ExileConfig config) {
    try {
      var cf = CertificateFactory.getInstance("X.509");
      var cert =
          (X509Certificate)
              cf.generateCertificate(
                  new ByteArrayInputStream(config.publicCert().getBytes(StandardCharsets.UTF_8)));
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = digest.digest(cert.getEncoded());
      return HexFormat.of().withDelimiter(":").formatHex(hash);
    } catch (Exception e) {
      return "";
    }
  }
}
