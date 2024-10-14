/* 
 *  Copyright 2017-2024 original authors
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
 */

package com.tcn.exile.config;

import com.tcn.exile.gateclients.UnconfiguredException;
import io.micronaut.context.event.ApplicationEvent;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotEmpty;

import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

@Serdeable
public class ConfigEvent extends ApplicationEvent {

  private boolean unconfigured = true;
  private String rootCert;
  private String publicCert;
  private String privateKey;
  private String fingerprintSha256;
  private String fingerprintSha256String;
  private String apiEndpoint;
  private String certificateName;
  private String certificateDescription;

  private String notnull(String val) {
    if (val == null) {
      return null;
    }
    if (val.trim().length() == 0) {
      return "empty";
    }
    return "masked";
  }
  @Override
  public String toString() {
    return "ConfigEvent{" +
        "unconfigured=" + unconfigured +
        ", rootCert='" + notnull(rootCert) + '\'' +
        ", publicCert='" + notnull(publicCert) + '\'' +
        ", privateKey='" + notnull(privateKey) + '\'' +
        ", fingerprintSha256='" + fingerprintSha256 + '\'' +
        ", fingerprintSha256String='" + fingerprintSha256String + '\'' +
        ", apiEndpoint='" + apiEndpoint + '\'' +
        ", certificateName='" + certificateName + '\'' +
        ", certificateDescription='" + certificateDescription + '\'' +
        '}';
  }

  public String getCertificateDescription() {
    return certificateDescription;
  }

  public void setCertificateDescription(String certificateDescription) {
    this.certificateDescription = certificateDescription;
  }

  public String getCertificateName() {
    return certificateName;
  }

  public void setCertificateName(String certificateName) {
    this.certificateName = certificateName;
  }

  public String getApiEndpoint() {
    return apiEndpoint;
  }

  public void setApiEndpoint(String apiEndpoint) {
    this.apiEndpoint = apiEndpoint;
  }

  public String getFingerprintSha256String() {
    return fingerprintSha256String;
  }

  public void setFingerprintSha256String(String fingerprintSha256String) {
    this.fingerprintSha256String = fingerprintSha256String;
  }

  public String getFingerprintSha256() {
    return fingerprintSha256;
  }

  public void setFingerprintSha256(String fingerprintSha256) {
    this.fingerprintSha256 = fingerprintSha256;
  }

  public String getPrivateKey() {
    return privateKey;
  }

  public void setPrivateKey(String privateKey) {
    this.privateKey = privateKey;
  }

  public String getPublicCert() {
    return publicCert;
  }

  public void setPublicCert(String publicCert) {
    this.publicCert = publicCert;
  }

  public String getRootCert() {
    return rootCert;
  }

  public void setRootCert(String rootCert) {
    this.rootCert = rootCert;
  }



  public boolean isUnconfigured() {
    return unconfigured;
  }

  public void setUnconfigured(boolean unconfigured) {
    this.unconfigured = unconfigured;
  }

  public ConfigEvent(Object source) {
    super(source);
  }

  public static Builder builder() {
    return new Builder();
  }



  public String getApiHostname() throws UnconfiguredException {
    try {
      return new URL(apiEndpoint).getHost();
    } catch (MalformedURLException e) {
      throw new UnconfiguredException(e);
    }
  }

  public int getApiPort() {
    try {
      var url = new URL(apiEndpoint);
      if (url.getPort() == -1) {
        return url.getDefaultPort();
      }
      return url.getPort();
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public Object getOrg() {
    var cert = this.getPublicCert();
    if ((cert == null) || (cert.isEmpty())) {
      return null;
    }
    try {
      X509Certificate myCert = (X509Certificate) CertificateFactory
          .getInstance("X509")
          .generateCertificate(
              new ByteArrayInputStream(cert.getBytes()));
      var principalString = myCert.getSubjectX500Principal().getName();
      for (String p : principalString.split(",")) {
        if (p.startsWith("O=")) {
          return p.substring(2);
        }
      }
      return null;
//      throw new UnconfiguredException("Can't find org id in certificate");
    } catch (CertificateException e) {
      e.printStackTrace();
      // throw new RuntimeException(e);
    }
    return null;

  }

  public Date getExpirationDate() {
      var cert = this.getPublicCert();
      if ((cert == null) || (cert.isEmpty())) {
        return null;
      }
      try {
        X509Certificate myCert = (X509Certificate) CertificateFactory
            .getInstance("X509")
            .generateCertificate(
                new ByteArrayInputStream(cert.getBytes()));
        return myCert.getNotAfter();
      } catch (CertificateException e) {
        e.printStackTrace();
        // throw new RuntimeException(e);
      }
      return null;
    }

  public static class Builder {
    private boolean unconfigured = true;
    private Object source;
    private String rootCert;
    private String publicCert;
    private String privateKey;
    private String fingerprintSha256;
    private String fingerprintSha256String;
    private String apiEndpoint;
    private String certificateName;
    private String certificateDescription;


    public ConfigEvent build() {
      ConfigEvent event = new ConfigEvent(this.source);
      event.unconfigured = this.unconfigured;
      event.rootCert = this.rootCert;
      event.publicCert = this.publicCert;
      event.privateKey = this.privateKey;
      event.fingerprintSha256 = this.fingerprintSha256;
      event.fingerprintSha256String = this.fingerprintSha256String;
      event.apiEndpoint = this.apiEndpoint;
      event.certificateName = this.certificateName;
      event.certificateDescription = this.certificateDescription;
      event.unconfigured = false;
      return event;
    }

    public Builder withUnconfigured(boolean unconfigured) {
      this.unconfigured = unconfigured;
      return this;
    }

    public Builder withSourceObject(Object source) {
      this.source = source;
      return this;
    }

    public Builder withRootCert(@NotEmpty String caCertificate) {
      this.rootCert = caCertificate;
      return this;
    }

    public Builder withPublicCert(@NotEmpty String certificate) {
      this.publicCert = certificate;
      return this;
    }

    public Builder withPrivateKey(@NotEmpty String privateKey) {
      this.privateKey = privateKey;
      return this;
    }

    public Builder withFingerprintSha256(@NotEmpty String fingerprintSha256) {
      this.fingerprintSha256 = fingerprintSha256;
      return this;
    }

    public Builder withFingerprintSha256String(@NotEmpty String fingerprintSha256String) {
      this.fingerprintSha256String = fingerprintSha256String;
      return this;
    }

    public Builder withApiEndpoint(@NotEmpty String apiEndpoint) {
      try {
        URI uri = new URI(apiEndpoint);
        this.apiEndpoint = apiEndpoint;
        return this;
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }

    public Builder withCertificateName(@NotEmpty String certificateName) {
      this.certificateName = certificateName;
      return this;
    }

    public Builder withCertificateDescription(@NotEmpty String certificateDescription) {
      this.certificateDescription = certificateDescription;
      return this;
    }

  }


}
