package com.tcn.exile.config;

import com.tcn.exile.ExileConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks certificate expiration and rotates via the gate config service.
 *
 * <p>Intended to be called periodically (e.g., every hour). If the certificate expires within the
 * configured threshold (default 10 days), it calls the gate service to rotate and writes the new
 * certificate to the config file.
 *
 * <p>Rotation issues a certificate on the gate before it can be persisted locally, and that step
 * cannot be undone. So the rotator refuses to start one it cannot finish: it fails on an unwritable
 * config file up front, and never rotates the same fingerprint twice. Without that second guard a
 * failed write turns into a new certificate every hour until the old one expires.
 */
public final class CertificateRotator {

  private static final Logger log = LoggerFactory.getLogger(CertificateRotator.class);
  private static final int DEFAULT_RENEWAL_DAYS = 10;

  private final ExileClientManager manager;
  private final int renewalDays;

  private String lastRotatedHash;
  private Instant lastRotatedAt;

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
      // Drop only the client: the watcher stays up, so writing a valid config into the
      // watched directory recovers the integration without a restart.
      log.error(
          "Certificate for org={} expired at {}. Dropping the client; it recovers when a valid"
              + " config is written to {}.",
          config.org(),
          expiration,
          configFileForLog());
      manager.dropClient();
      return false;
    }

    if (!expiration.isBefore(now.plus(renewalDays, ChronoUnit.DAYS))) {
      log.debug(
          "Certificate valid until {}, no rotation needed ({} day threshold)",
          expiration,
          renewalDays);
      return false;
    }

    var hash = getCertFingerprint(config);
    if (hash.isEmpty()) {
      log.error("Cannot rotate for org={}: no usable certificate fingerprint", config.org());
      return false;
    }

    if (hash.equals(lastRotatedHash)) {
      log.error(
          "Certificate for org={} was rotated at {} but the client is still using the old one, so"
              + " the write or the reload failed. Not rotating again. Check that {} is writable and"
              + " that the watcher reloaded it.",
          config.org(),
          lastRotatedAt,
          configFileForLog());
      return false;
    }

    try {
      manager.configWatcher().assertWritable();
    } catch (IOException | RuntimeException e) {
      log.error(
          "Cannot rotate for org={}: {}. Mount the config directory read-write.",
          config.org(),
          e.getMessage());
      return false;
    }

    log.info("Certificate for org={} expires at {}, rotating", config.org(), expiration);
    try {
      var newCert = client.config_().rotateCertificate(hash);
      if (newCert == null || newCert.isEmpty()) {
        log.error("Certificate rotation for org={} returned an empty response", config.org());
        return false;
      }

      // The gate has issued it, so record that now. A later failure must not be retried
      // into a second certificate.
      lastRotatedHash = hash;
      lastRotatedAt = now;

      if (ConfigParser.parse(newCert.getBytes(StandardCharsets.UTF_8)).isEmpty()) {
        log.error(
            "Certificate rotation for org={} returned a config that does not parse; not writing it",
            config.org());
        return false;
      }

      manager.configWatcher().writeConfig(newCert);
      log.info("Certificate rotated for org={}", config.org());
      return true;
    } catch (Exception e) {
      log.error("Certificate rotation failed for org={}: {}", config.org(), e.getMessage(), e);
      return false;
    }
  }

  private String configFileForLog() {
    try {
      return manager.configWatcher().configFile().toString();
    } catch (RuntimeException e) {
      return "the config file";
    }
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

  /**
   * The gate's lookup key for the configured certificate. Prefers the fingerprint the gate shipped
   * in the config file over a locally computed one — a digest formatted differently from the gate's
   * key makes every rotation fail with "exile certificate not found".
   */
  static String getCertFingerprint(ExileConfig config) {
    var provided = config.certificateFingerprint();
    if (provided != null && !provided.isBlank()) {
      return provided.trim().toLowerCase(Locale.ROOT);
    }
    return computeCertFingerprint(config);
  }

  static String computeCertFingerprint(ExileConfig config) {
    try {
      var cf = CertificateFactory.getInstance("X.509");
      var cert =
          (X509Certificate)
              cf.generateCertificate(
                  new ByteArrayInputStream(config.publicCert().getBytes(StandardCharsets.UTF_8)));
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = digest.digest(cert.getEncoded());
      // Bare lowercase hex, no delimiter — must match the gate's hex(sha256(DER)) lookup key.
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      return "";
    }
  }
}
