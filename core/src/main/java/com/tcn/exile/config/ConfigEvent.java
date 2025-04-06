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

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tcn.exile.gateclients.ConfigEventInterface;
import com.tcn.exile.gateclients.ConfigInterface;

import io.micronaut.context.event.ApplicationEvent;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotEmpty;

import java.net.URI;
import java.net.URISyntaxException;

@Serdeable
public class ConfigEvent extends ApplicationEvent implements ConfigEventInterface {

  private static final Logger log = LoggerFactory.getLogger(ConfigEvent.class);
  
  private final Config config;
  private EventType eventType;

  public ConfigEvent(Object source, Config config, EventType eventType) {
    super(source);
    this.config = Objects.requireNonNull(config, "Config cannot be null in ConfigEvent constructor");
    this.eventType = Objects.requireNonNull(eventType, "EventType cannot be null");
  }
  
  public ConfigEvent(Object source) {
    super(source);
    this.config = new Config();
    this.eventType = EventType.UPDATE;
  }

  @Override
  public String toString() {
    return "ConfigEvent{" +
           "config=" + config.toString() +
           ", eventType=" + eventType +
           '}';
  }

  @Override
  public ConfigInterface getConfig() {
    return this.config;
  }

  @Override
  public EventType getEventType() {
    return eventType;
  }

  public static class Builder {
    private Object source = "ConfigEvent.Builder";
    private Config config = new Config();
    private EventType eventType = EventType.UPDATE;

    public ConfigEvent build() {
      if (this.config.isUnconfigured()) {
          log.warn("Building ConfigEvent with unconfigured internal state. Setting configured.");
          this.config.setUnconfigured(false);
      }
      return new ConfigEvent(this.source, this.config, this.eventType);
    }

    public Builder withConfig(Config config) {
      this.config = config;
      return this;
    }
    

    public Builder withUnconfigured(boolean unconfigured) {
      this.config.setUnconfigured(unconfigured);
      return this;
    }

    public Builder withSourceObject(Object source) {
      this.source = source;
      return this;
    }

    public Builder withRootCert(@NotEmpty String caCertificate) {
      this.config.setRootCert(caCertificate);
      return this;
    }

    public Builder withPublicCert(@NotEmpty String certificate) {
      this.config.setPublicCert(certificate);
      return this;
    }

    public Builder withPrivateKey(@NotEmpty String privateKey) {
      this.config.setPrivateKey(privateKey);
      return this;
    }

    public Builder withFingerprintSha256(@NotEmpty String fingerprintSha256) {
      this.config.setFingerprintSha256(fingerprintSha256);
      return this;
    }

    public Builder withFingerprintSha256String(@NotEmpty String fingerprintSha256String) {
      this.config.setFingerprintSha256String(fingerprintSha256String);
      return this;
    }

    public Builder withApiEndpoint(@NotEmpty String apiEndpoint) {
        try {
            new URI(apiEndpoint); 
            this.config.setApiEndpoint(apiEndpoint);
            return this;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid API endpoint URI syntax", e);
        } 
    }

    public Builder withCertificateName(@NotEmpty String certificateName) {
      this.config.setCertificateName(certificateName);
      return this;
    }

    public Builder withCertificateDescription(@NotEmpty String certificateDescription) {
      this.config.setCertificateDescription(certificateDescription);
      return this;
    }

    public Builder withEventType(EventType eventType) {
      this.eventType = Objects.requireNonNull(eventType, "EventType cannot be null");
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }
} 