/*
 *  (C) 2017-2024 TCN Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tcn.exile.config;

import com.tcn.exile.gateclients.UnconfiguredException;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Concrete implementation of ConfigInterface that stores configuration data. */
@Serdeable
public class Config {
  private static final List<String> fieldNames =
      List.of(
          "ca_certificate",
          "certificate",
          "private_key",
          "fingerprint_sha256",
          "fingerprint_sha256_string",
          "api_endpoint",
          "certificate_name",
          "certificate_description");
  private static final Logger log = LoggerFactory.getLogger(Config.class);

  private boolean unconfigured;
  private String rootCert;
  private String publicCert;
  private String privateKey;
  private String fingerprintSha256;
  private String fingerprintSha256String;
  private String apiEndpoint;
  private String certificateName;
  private String certificateDescription;
  private String org = null;

  /** Default constructor - initializes as unconfigured. */
  public Config() {
    this.unconfigured = true;
  }

  /**
   * Copy constructor
   *
   * @param source The source config to copy from
   */
  public Config(Config source) {
    if (source != null) {
      this.unconfigured = source.isUnconfigured();
      this.rootCert = source.getRootCert();
      this.publicCert = source.getPublicCert();
      this.privateKey = source.getPrivateKey();
      this.fingerprintSha256 = source.getFingerprintSha256();
      this.fingerprintSha256String = source.getFingerprintSha256String();
      this.apiEndpoint = source.getApiEndpoint();
      this.certificateName = source.getCertificateName();
      this.certificateDescription = source.getCertificateDescription();
    }
  }

  public static Optional<Config> of(@NotNull byte[] data, @NotNull ObjectMapper objectMapper) {
    try {
      var map =
          objectMapper.readValue(
              Base64.getDecoder().decode(data), Argument.mapOf(String.class, String.class));
      if (!map.keySet().containsAll(fieldNames)) {
        log.error("Invalid certificate data format");
        return Optional.empty();
      }
      var ret =
          Optional.of(
              Config.builder()
                  .rootCert(map.get("ca_certificate"))
                  .publicCert(map.get("certificate"))
                  .privateKey(map.get("private_key"))
                  .fingerprintSha256(map.get("fingerprint_sha256"))
                  .fingerprintSha256String(map.get("fingerprint_sha256_string"))
                  .apiEndpoint(map.get("api_endpoint"))
                  .certificateName(map.get("certificate_name"))
                  .certificateDescription(map.get("certificate_description"))
                  .build());
      if (ret.get().getOrg() == null) {
        log.error("Parsing certificate failed");
        return Optional.empty();
      }
      return ret;
    } catch (IOException e) {
      log.debug("Parsing error", e);
      return Optional.empty();
    }
  }

  /**
   * Constructor that takes a base64 encoded JSON string and parses it.
   *
   * @param base64EncodedJson The base64 encoded JSON string to parse
   * @param objectMapper The ObjectMapper to use for JSON deserialization
   * @throws UnconfiguredException If parsing fails
   */
  public Config(String base64EncodedJson, ObjectMapper objectMapper) throws UnconfiguredException {
    if (base64EncodedJson == null || base64EncodedJson.isEmpty()) {
      throw new UnconfiguredException("Base64 encoded JSON string cannot be null or empty");
    }

    try {
      byte[] jsonBytes = Base64.getDecoder().decode(base64EncodedJson);
      @SuppressWarnings("unchecked") // Suppress warning for Map cast
      Map<String, String> jsonMap = objectMapper.readValue(jsonBytes, Map.class);

      this.rootCert = jsonMap.get("ca_certificate");
      this.publicCert = jsonMap.get("certificate");
      this.privateKey = jsonMap.get("private_key");
      this.fingerprintSha256 = jsonMap.get("fingerprint_sha256");
      this.fingerprintSha256String = jsonMap.get("fingerprint_sha256_string");
      this.apiEndpoint = jsonMap.get("api_endpoint");
      this.certificateName = jsonMap.get("certificate_name");
      this.certificateDescription = jsonMap.get("certificate_description");
      this.unconfigured = false;

      log.debug("Parsed base64 encoded JSON successfully");
    } catch (IOException e) {
      throw new UnconfiguredException("Failed to parse JSON", e);
    } catch (IllegalArgumentException e) {
      throw new UnconfiguredException("Invalid base64 string", e);
    }
  }

  /**
   * Private constructor for the Builder pattern.
   *
   * @param builder The builder instance to initialize from.
   */
  private Config(Builder builder) {
    this.unconfigured = builder.unconfigured;
    this.rootCert = builder.rootCert;
    this.publicCert = builder.publicCert;
    this.privateKey = builder.privateKey;
    this.fingerprintSha256 = builder.fingerprintSha256;
    this.fingerprintSha256String = builder.fingerprintSha256String;
    this.apiEndpoint = builder.apiEndpoint;
    this.certificateName = builder.certificateName;
    this.certificateDescription = builder.certificateDescription;
  }

  /**
   * Static factory method to create a Config from a base64 encoded JSON string.
   *
   * @param base64EncodedJson The base64 encoded JSON string to parse
   * @param objectMapper The ObjectMapper to use for JSON deserialization
   * @return A new Config instance
   * @throws UnconfiguredException If parsing fails
   */
  public static Config fromBase64Json(String base64EncodedJson, ObjectMapper objectMapper)
      throws UnconfiguredException {
    return new Config(base64EncodedJson, objectMapper);
  }

  /**
   * Static factory method to get a new Builder instance.
   *
   * @return A new Builder instance.
   */
  public static Builder builder() {
    return new Builder();
  }

  // --- Getters ---

  public String getCertificateDescription() {
    return certificateDescription;
  }

  public String getCertificateName() {
    return certificateName;
  }

  public String getApiEndpoint() {
    return apiEndpoint;
  }

  public String getFingerprintSha256() {
    return fingerprintSha256;
  }

  public String getFingerprintSha256String() {
    return fingerprintSha256String;
  }

  public boolean isUnconfigured() {
    return unconfigured;
  }

  public String getRootCert() {
    return rootCert;
  }

  public String getPublicCert() {
    return publicCert;
  }

  public String getPrivateKey() {
    return privateKey;
  }

  // --- Setters (Potentially make these package-private or remove if Builder is preferred) ---
  // Note: Setters are kept public for now to maintain compatibility with ConfigEvent.Builder and
  // direct usage.
  // Consider changing visibility if strict immutability via Builder is desired.

  public void setCertificateDescription(String certificateDescription) {
    this.certificateDescription = certificateDescription;
  }

  public void setCertificateName(String certificateName) {
    this.certificateName = certificateName;
  }

  public void setApiEndpoint(String apiEndpoint) {
    this.apiEndpoint = apiEndpoint;
  }

  public void setFingerprintSha256(String fingerprintSha256) {
    this.fingerprintSha256 = fingerprintSha256;
  }

  public void setFingerprintSha256String(String fingerprintSha256String) {
    this.fingerprintSha256String = fingerprintSha256String;
  }

  public void setUnconfigured(boolean unconfigured) {
    this.unconfigured = unconfigured;
  }

  public void setRootCert(String rootCert) {
    this.rootCert = rootCert;
  }

  public void setPublicCert(String publicCert) {
    this.publicCert = publicCert;
  }

  public void setPrivateKey(String privateKey) {
    this.privateKey = privateKey;
  }

  // --- Derived Data Methods ---

  public String getOrg() {
    if (this.org != null) {
      return this.org;
    }
    try {
      if (publicCert == null || publicCert.isBlank()) {
        return null;
      }
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      ByteArrayInputStream bais = new ByteArrayInputStream(publicCert.getBytes());
      X509Certificate cert = (X509Certificate) cf.generateCertificate(bais);
      String dn = cert.getSubjectX500Principal().getName();
      log.debug("Certificate Subject: " + dn);

      // Extract CN from DN
      for (String part : dn.split(",")) {
        if (part.trim().startsWith("O=")) {
          this.org = part.substring(2).trim();
          return this.org;
        }
      }
      return null;
    } catch (CertificateException e) {
      log.error("Error parsing certificate for getOrg: {}", e.getMessage());
      return null;
    } catch (Exception e) {
      log.error("Unexpected error in getOrg", e);
      return null;
    }
  }

  public String getApiHostname() throws UnconfiguredException {
    if (apiEndpoint == null || apiEndpoint.isBlank()) {
      throw new UnconfiguredException("API endpoint is not set");
    }
    try {
      return new URL(apiEndpoint).getHost();
    } catch (MalformedURLException e) {
      throw new UnconfiguredException("Invalid API endpoint URL: " + apiEndpoint, e);
    }
  }

  public int getApiPort() throws UnconfiguredException {
    if (apiEndpoint == null || apiEndpoint.isBlank()) {
      throw new UnconfiguredException("API endpoint is not set");
    }
    try {
      var url = new URL(apiEndpoint);
      if (url.getPort() == -1) {
        // Use default HTTPS port if not specified
        return 443;
      }
      return url.getPort();
    } catch (MalformedURLException e) {
      throw new UnconfiguredException("Invalid API endpoint URL: " + apiEndpoint, e);
    }
  }

  public Date getExpirationDate() {
    var certStr = this.getPublicCert();
    if (certStr == null || certStr.isBlank()) {
      return null;
    }
    try {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      X509Certificate myCert =
          (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certStr.getBytes()));
      return myCert.getNotAfter();
    } catch (CertificateException e) {
      log.error("Error parsing certificate for expiration date: {}", e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error getting expiration date", e);
    }
    return null;
  }

  // --- Object Methods ---

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || !(o instanceof Config)) return false;

    Config that = (Config) o;

    // Consider adding more fields for a more robust equality check if needed
    if (unconfigured != that.isUnconfigured()) return false;
    if (!Objects.equals(apiEndpoint, that.getApiEndpoint())) return false;
    if (!Objects.equals(getOrg(), that.getOrg())) return false; // Derived, might be slow
    return Objects.equals(publicCert, that.getPublicCert());
  }

  @Override
  public int hashCode() {
    // Consider adding more fields for a more robust hash code if needed
    return Objects.hash(unconfigured, apiEndpoint, getOrg(), publicCert);
  }

  @Override
  public String toString() {
    return "Config{"
        + "unconfigured="
        + unconfigured
        + ", apiEndpoint='"
        + apiEndpoint
        + '\''
        + ", certificateName='"
        + certificateName
        + '\''
        + ", org='"
        + getOrg()
        + "',..."
        + // Be mindful of potential null from getOrg()
        '}';
  }

  // --- Builder Class ---

  public static class Builder {
    private boolean unconfigured = true; // Default to unconfigured
    private String rootCert;
    private boolean rootCertSet = false;
    private String publicCert;
    private boolean publicCertSet = false;
    private String privateKey;
    private boolean privateKeySet = false;
    private String fingerprintSha256;
    private boolean fingerprintSha256Set = false;
    private String fingerprintSha256String;
    private boolean fingerprintSha256StringSet = false;
    private String apiEndpoint;
    private boolean apiEndpointSet = false;
    private String certificateName;
    private boolean certificateNameSet = false;
    private String certificateDescription;
    private boolean certificateDescriptionSet = false;

    public Builder rootCert(@NotEmpty String rootCert) {
      this.rootCert = rootCert;
      this.rootCertSet = true;
      return this;
    }

    public Builder publicCert(@NotEmpty String publicCert) {
      this.publicCert = publicCert;
      this.publicCertSet = true;
      return this;
    }

    public Builder privateKey(@NotEmpty String privateKey) {
      this.privateKey = privateKey;
      this.privateKeySet = true;
      return this;
    }

    public Builder fingerprintSha256(@NotEmpty String fingerprintSha256) {
      this.fingerprintSha256 = fingerprintSha256;
      this.fingerprintSha256Set = true;
      return this;
    }

    public Builder fingerprintSha256String(@NotEmpty String fingerprintSha256String) {
      this.fingerprintSha256String = fingerprintSha256String;
      this.fingerprintSha256Set = true;
      return this;
    }

    public Builder apiEndpoint(@NotEmpty String apiEndpoint) {
      this.apiEndpoint = apiEndpoint;
      this.apiEndpointSet = true;
      return this;
    }

    public Builder certificateName(@NotEmpty String certificateName) {
      this.certificateName = certificateName;
      this.certificateNameSet = true;
      return this;
    }

    public Builder certificateDescription(String certificateDescription) {
      this.certificateDescription = certificateDescription; // Can be null or empty
      this.certificateDescriptionSet = true;
      return this;
    }

    /**
     * Builds the Config object. It automatically sets unconfigured to false if essential fields
     * (e.g., apiEndpoint, publicCert) are set. Add more validation here if needed.
     *
     * @return A new Config instance.
     */
    public Config build() {
      this.unconfigured =
          !(this.rootCertSet
              && this.publicCertSet
              && this.privateKeySet
              && this.fingerprintSha256Set
              && this.fingerprintSha256StringSet
              && this.apiEndpointSet
              && this.certificateNameSet
              && this.certificateDescriptionSet);

      // Automatically mark as configured if key fields are provided
      if (this.unconfigured && apiEndpoint != null && publicCert != null) {
        log.debug("Builder automatically marking config as configured based on provided fields.");
        this.unconfigured = false;
      }
      return new Config(this);
    }
  }
}
