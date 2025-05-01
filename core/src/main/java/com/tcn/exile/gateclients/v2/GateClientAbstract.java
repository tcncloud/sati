/*
 *  (C) 2017-2025 TCN Inc. All rights reserved.
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
package com.tcn.exile.gateclients.v2;

import com.tcn.exile.config.Config;
import com.tcn.exile.gateclients.UnconfiguredException;
import io.grpc.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GateClientAbstract {
  private static final Logger log = LoggerFactory.getLogger(GateClientAbstract.class);

  // Static channel shared by all instances, managed centrally
  private ManagedChannel sharedChannel;
  private static final ReentrantLock lock = new ReentrantLock();

  private Config currentConfig = null;
  protected final String tenant;

  public GateClientAbstract(String tenant, Config currentConfig) {
    this.currentConfig = currentConfig;
    this.tenant = tenant;
  }

  public Config getConfig() {
    return currentConfig;
  }

  public boolean isUnconfigured() {
    if (currentConfig == null) {
      return true;
    }
    return currentConfig.isUnconfigured();
  }

  public Map<String, Object> getStatus() {
    return Map.of(
        "running", true,
        "configured", !getConfig().isUnconfigured(),
        "api_endpoint", getConfig().getApiEndpoint(),
        "org", getConfig().getOrg(),
        "expiration_date", getConfig().getExpirationDate(),
        "certificate_expiration_date", getConfig().getExpirationDate(),
        "certificate_description", getConfig().getCertificateDescription());
  }

  protected void shutdown() {
    log.debug("Tenant: {} - Attempting shutdown of static shared gRPC channel.", tenant);
    // Synchronize access to the shared channel for shutdown
    if (lock.tryLock()) {
      ManagedChannel channelToShutdown = sharedChannel; // Work with local variable inside lock
      if ((channelToShutdown != null)
          && (!channelToShutdown.isShutdown() && !channelToShutdown.isTerminated())) {
        log.debug(
            "Tenant: {} - Attempting graceful shutdown of shared channel {}",
            tenant,
            channelToShutdown);
        channelToShutdown.shutdown();
        try {
          if (!channelToShutdown.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn(
                "Tenant: {} - Shared channel {} did not terminate gracefully after 10s, forcing shutdown.",
                tenant,
                channelToShutdown);
            channelToShutdown.shutdownNow();
            if (!channelToShutdown.awaitTermination(5, TimeUnit.SECONDS)) {
              log.error(
                  "Tenant: {} - Shared channel {} failed to terminate even after forced shutdown.",
                  tenant,
                  channelToShutdown);
            }
          }
          log.info(
              "Tenant: {} - Successfully shut down shared channel {}", tenant, channelToShutdown);
        } catch (InterruptedException e) {
          log.error(
              "Tenant: {} - Interrupted while waiting for shared channel shutdown, forcing shutdown now.",
              tenant,
              e);
          channelToShutdown.shutdownNow();
          Thread.currentThread().interrupt(); // Preserve interrupt status
        }
        // Set static field to null AFTER successful shutdown
        sharedChannel = null;
      } else {
        log.debug("Tenant: {} - Shared channel is already null, shut down, or terminated.", tenant);
        // Ensure static field is null if channel is unusable
        if (sharedChannel != null && (sharedChannel.isShutdown() || sharedChannel.isTerminated())) {
          sharedChannel = null;
        }
      }
    } else {
      return;
    }
    lock.unlock();
  }

  public ManagedChannel getChannel() throws UnconfiguredException {
    ManagedChannel localChannel = sharedChannel; // Read volatile field once
    if ((localChannel != null) && !localChannel.isShutdown() && !localChannel.isTerminated()) {
      return localChannel;
    }

    // Synchronize channel creation using the static lock
    try {
      if (lock.tryLock(10, TimeUnit.SECONDS)) {
        // Double-check condition inside synchronized block
        localChannel = sharedChannel;
        if ((localChannel != null) && !localChannel.isShutdown() && !localChannel.isTerminated()) {
          lock.unlock();
          return localChannel;
        }

        log.debug("Tenant: {} - Creating a new static shared gRPC channel.", tenant);

        try {
          var channelCredentials =
              TlsChannelCredentials.newBuilder()
                  .trustManager(new ByteArrayInputStream(getConfig().getRootCert().getBytes()))
                  .keyManager(
                      new ByteArrayInputStream(getConfig().getPublicCert().getBytes()),
                      new ByteArrayInputStream(getConfig().getPrivateKey().getBytes()))
                  .build();

          ManagedChannel newChannel =
              Grpc.newChannelBuilderForAddress(
                      getConfig().getApiHostname(), getConfig().getApiPort(), channelCredentials)
                  .keepAliveTime(1, TimeUnit.SECONDS)
                  .keepAliveTimeout(10, TimeUnit.SECONDS)
                  .idleTimeout(30, TimeUnit.MINUTES)
                  .overrideAuthority("exile-proxy")
                  .build();

          sharedChannel = newChannel; // Assign the new channel to the static field
          log.debug("Tenant: {} - New static shared channel created: {}", tenant, newChannel);
          lock.unlock();
          return newChannel;
        } catch (IOException e) {
          log.error("Tenant: {} - IOException during shared channel creation", tenant, e);
          lock.unlock();
          throw new UnconfiguredException(
              "TCN Gate client configuration error during channel creation", e);
        } catch (UnconfiguredException e) {
          log.error("Tenant: {} - Configuration error during shared channel creation", tenant, e);
          lock.unlock();
          throw e; // Re-throw specific unconfigured exception
        } catch (Exception e) {
          log.error("Tenant: {} - Unexpected error during shared channel creation", tenant, e);
          lock.unlock();
          throw new UnconfiguredException(
              "Unexpected error configuring TCN Gate client channel", e);
        } finally {
          lock.unlock();
        }
      }
    } catch (Exception ex) {
    }
    return sharedChannel;
  }

  public abstract void start();

  /** Resets the gRPC channel after a connection failure */
  protected void resetChannel() {
    log.info("Tenant: {} - Resetting static shared gRPC channel after connection failure.", tenant);
    // Simply call the synchronized shutdown method which handles the static channel
    shutdown();
  }

  /**
   * Helper method to handle StatusRuntimeException
   *
   * @param e The exception to handle
   * @return true if the exception was handled
   */
  protected boolean handleStatusRuntimeException(StatusRuntimeException e) {
    if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
      log.warn(
          "Tenant: {} - Connection unavailable, resetting channel: {}", tenant, e.getMessage());
      resetChannel();
      return true;
    }
    return false;
  }
}
