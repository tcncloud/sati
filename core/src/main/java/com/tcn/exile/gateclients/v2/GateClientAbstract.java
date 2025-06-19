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
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GateClientAbstract {
  private static final Logger log = LoggerFactory.getLogger(GateClientAbstract.class);

  // Properly static channel shared by all instances with thread-safe management
  private static volatile ManagedChannel sharedChannel;
  private static final ReentrantLock lock = new ReentrantLock();
  private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

  private Config currentConfig = null;
  protected final String tenant;

  // Register shutdown hook to ensure proper cleanup
  static {
    registerShutdownHook();
  }

  private static void registerShutdownHook() {
    if (shutdownHookRegistered.compareAndSet(false, true)) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    log.info("Shutdown hook triggered - cleaning up gRPC channels");
                    forceShutdownSharedChannel();
                  },
                  "gRPC-Channel-Cleanup"));
    }
  }

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
        "certificate_description", getConfig().getCertificateDescription(),
        "channel_active", isChannelActive());
  }

  private boolean isChannelActive() {
    ManagedChannel channel = sharedChannel;
    return channel != null && !channel.isShutdown() && !channel.isTerminated();
  }

  protected void shutdown() {
    log.debug("Tenant: {} - Attempting shutdown of static shared gRPC channel.", tenant);

    lock.lock();
    try {
      ManagedChannel channelToShutdown = sharedChannel;
      if (channelToShutdown != null
          && !channelToShutdown.isShutdown()
          && !channelToShutdown.isTerminated()) {
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
    } finally {
      lock.unlock(); // Guaranteed unlock in finally block
    }
  }

  private static void forceShutdownSharedChannel() {
    lock.lock();
    try {
      ManagedChannel channelToShutdown = sharedChannel;
      if (channelToShutdown != null) {
        log.info("Force shutting down shared gRPC channel during application shutdown");
        channelToShutdown.shutdownNow();
        try {
          if (!channelToShutdown.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("Shared channel did not terminate within 5 seconds during force shutdown");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Interrupted during force shutdown of shared channel");
        }
        sharedChannel = null;
      }
    } finally {
      lock.unlock();
    }
  }

  public ManagedChannel getChannel() throws UnconfiguredException {
    // Double-checked locking pattern for thread-safe lazy initialization
    ManagedChannel localChannel = sharedChannel;
    if (localChannel == null || localChannel.isShutdown() || localChannel.isTerminated()) {

      lock.lock();
      try {
        // Double-check condition inside synchronized block
        localChannel = sharedChannel;
        if (localChannel == null || localChannel.isShutdown() || localChannel.isTerminated()) {
          log.debug("Tenant: {} - Creating a new static shared gRPC channel.", tenant);
          localChannel = createNewChannel();
          sharedChannel = localChannel;
          log.debug("Tenant: {} - New static shared channel created: {}", tenant, localChannel);
        }
      } finally {
        lock.unlock();
      }
    }

    return localChannel;
  }

  private ManagedChannel createNewChannel() throws UnconfiguredException {
    try {
      var channelCredentials =
          TlsChannelCredentials.newBuilder()
              .trustManager(new ByteArrayInputStream(getConfig().getRootCert().getBytes()))
              .keyManager(
                  new ByteArrayInputStream(getConfig().getPublicCert().getBytes()),
                  new ByteArrayInputStream(getConfig().getPrivateKey().getBytes()))
              .build();

      return Grpc.newChannelBuilderForAddress(
              getConfig().getApiHostname(), getConfig().getApiPort(), channelCredentials)
          .keepAliveTime(1, TimeUnit.SECONDS)
          .keepAliveTimeout(10, TimeUnit.SECONDS)
          .idleTimeout(30, TimeUnit.MINUTES)
          .overrideAuthority("exile-proxy")
          .build();

    } catch (IOException e) {
      log.error("Tenant: {} - IOException during shared channel creation", tenant, e);
      throw new UnconfiguredException(
          "TCN Gate client configuration error during channel creation", e);
    } catch (UnconfiguredException e) {
      log.error("Tenant: {} - Configuration error during shared channel creation", tenant, e);
      throw e; // Re-throw specific unconfigured exception
    } catch (Exception e) {
      log.error("Tenant: {} - Unexpected error during shared channel creation", tenant, e);
      throw new UnconfiguredException("Unexpected error configuring TCN Gate client channel", e);
    }
  }

  public abstract void start();

  /** Resets the gRPC channel after a connection failure */
  protected void resetChannel() {
    log.info("Tenant: {} - Resetting static shared gRPC channel after connection failure.", tenant);
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

  /** Get channel statistics for monitoring */
  public Map<String, Object> getChannelStats() {
    ManagedChannel channel = sharedChannel;
    if (channel != null) {
      return Map.of(
          "isShutdown", channel.isShutdown(),
          "isTerminated", channel.isTerminated(),
          "authority", channel.authority(),
          "state", channel.getState(false).toString());
    }
    return Map.of("channel", "null");
  }
}
