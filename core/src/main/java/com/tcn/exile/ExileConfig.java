package com.tcn.exile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Objects;

/**
 * Immutable configuration for connecting to the Exile gate server.
 *
 * <p>Holds mTLS credentials and server endpoint information. Built via {@link #builder()}.
 */
public final class ExileConfig {

  private final String rootCert;
  private final String publicCert;
  private final String privateKey;
  private final String apiHostname;
  private final int apiPort;

  // Lazily derived from certificate.
  private volatile String org;

  private ExileConfig(Builder builder) {
    this.rootCert = Objects.requireNonNull(builder.rootCert, "rootCert");
    this.publicCert = Objects.requireNonNull(builder.publicCert, "publicCert");
    this.privateKey = Objects.requireNonNull(builder.privateKey, "privateKey");
    this.apiHostname = Objects.requireNonNull(builder.apiHostname, "apiHostname");
    this.apiPort = builder.apiPort > 0 ? builder.apiPort : 443;
  }

  public String rootCert() {
    return rootCert;
  }

  public String publicCert() {
    return publicCert;
  }

  public String privateKey() {
    return privateKey;
  }

  public String apiHostname() {
    return apiHostname;
  }

  public int apiPort() {
    return apiPort;
  }

  /** Extracts the organization name from the certificate CN field. Thread-safe. */
  public String org() {
    String result = org;
    if (result == null) {
      synchronized (this) {
        result = org;
        if (result == null) {
          result = parseOrgFromCert();
          org = result;
        }
      }
    }
    return result;
  }

  private String parseOrgFromCert() {
    try {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      X509Certificate cert =
          (X509Certificate)
              cf.generateCertificate(
                  new ByteArrayInputStream(publicCert.getBytes(StandardCharsets.UTF_8)));
      String dn = cert.getSubjectX500Principal().getName();
      for (String part : dn.split(",")) {
        String trimmed = part.trim();
        if (trimmed.startsWith("O=")) {
          return trimmed.substring(2);
        }
      }
      return "";
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse organization from certificate", e);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String rootCert;
    private String publicCert;
    private String privateKey;
    private String apiHostname;
    private int apiPort;

    private Builder() {}

    public Builder rootCert(String rootCert) {
      this.rootCert = rootCert;
      return this;
    }

    public Builder publicCert(String publicCert) {
      this.publicCert = publicCert;
      return this;
    }

    public Builder privateKey(String privateKey) {
      this.privateKey = privateKey;
      return this;
    }

    public Builder apiHostname(String apiHostname) {
      this.apiHostname = apiHostname;
      return this;
    }

    public Builder apiPort(int apiPort) {
      this.apiPort = apiPort;
      return this;
    }

    public ExileConfig build() {
      return new ExileConfig(this);
    }
  }
}
