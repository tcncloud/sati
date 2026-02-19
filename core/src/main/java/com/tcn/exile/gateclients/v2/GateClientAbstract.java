/*
 *  (C) 2017-2026 TCN Inc. All rights reserved.
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for bidirectional gRPC stream clients.
 *
 * <p>Provides shared infrastructure for stream lifecycle management including exponential backoff
 * with jitter, hung connection detection, connection tracking, and graceful shutdown. Subclasses
 * implement {@link #runStream()} for stream-specific logic and {@link #getStreamName()} for
 * logging.
 */
public abstract class GateClientAbstract {
  private static final Logger log = LoggerFactory.getLogger(GateClientAbstract.class);

  // Stream lifecycle constants
  protected static final long STREAM_TIMEOUT_MINUTES = 5;
  protected static final long HUNG_CONNECTION_THRESHOLD_SECONDS = 45;

  // Backoff configuration
  private static final long BACKOFF_BASE_MS = 2000;
  private static final long BACKOFF_MAX_MS = 30000;
  private static final double BACKOFF_JITTER = 0.2;

  // Channel management
  private ManagedChannel sharedChannel;
  private final ReentrantLock lock = new ReentrantLock();

  private Config currentConfig = null;
  protected final String tenant;

  // Connection tracking — shared across all stream subclasses
  protected final AtomicReference<Instant> lastMessageTime = new AtomicReference<>();
  protected final AtomicReference<Instant> lastDisconnectTime = new AtomicReference<>();
  protected final AtomicReference<Instant> reconnectionStartTime = new AtomicReference<>();
  protected final AtomicReference<Instant> connectionEstablishedTime = new AtomicReference<>();
  protected final AtomicLong totalReconnectionAttempts = new AtomicLong(0);
  protected final AtomicLong successfulReconnections = new AtomicLong(0);
  protected final AtomicReference<String> lastErrorType = new AtomicReference<>();
  protected final AtomicLong consecutiveFailures = new AtomicLong(0);
  protected final AtomicBoolean isRunning = new AtomicBoolean(false);

  public GateClientAbstract(String tenant, Config currentConfig) {
    this.currentConfig = currentConfig;
    this.tenant = tenant;

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Shutdown hook triggered - cleaning up gRPC channels");
                  forceShutdownSharedChannel();
                },
                "gRPC-Channel-Cleanup"));
  }

  // ---------------------------------------------------------------------------
  // Stream lifecycle — template method pattern
  // ---------------------------------------------------------------------------

  /**
   * Human-readable name for this stream, used in log messages. Override in subclasses that use the
   * template {@link #start()} method (e.g. "EventStream", "JobQueue").
   */
  protected String getStreamName() {
    return getClass().getSimpleName();
  }

  /**
   * Run a single stream session. Called by the template {@link #start()} method. Subclasses that
   * use the shared lifecycle must override this. Legacy subclasses that override start() directly
   * can ignore it.
   */
  protected void runStream()
      throws UnconfiguredException, InterruptedException, HungConnectionException {
    throw new UnsupportedOperationException(
        getStreamName() + " must override runStream() or start()");
  }

  /**
   * Template method that handles the full stream lifecycle: backoff, try/catch, error tracking.
   * Each invocation represents one connection attempt.
   */
  public void start() {
    if (isUnconfigured()) {
      log.warn("{} is unconfigured, cannot start", getStreamName());
      return;
    }

    // Prevent concurrent invocations from the fixed-rate scheduler
    if (!isRunning.compareAndSet(false, true)) {
      return;
    }

    // Exponential backoff: sleep before retrying if we have consecutive failures
    long backoffMs = computeBackoffMs();
    if (backoffMs > 0) {
      log.debug(
          "[{}] Waiting {}ms before reconnect (consecutive failures: {})",
          getStreamName(),
          backoffMs,
          consecutiveFailures.get());
      try {
        Thread.sleep(backoffMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        isRunning.set(false);
        return;
      }
    }

    try {
      log.debug("[{}] Starting, checking configuration status", getStreamName());
      reconnectionStartTime.set(Instant.now());
      resetChannelBackoff();
      runStream();
    } catch (HungConnectionException e) {
      log.warn("[{}] Connection appears hung: {}", getStreamName(), e.getMessage());
      lastErrorType.set("HungConnection");
      consecutiveFailures.incrementAndGet();
    } catch (UnconfiguredException e) {
      log.error("[{}] Configuration error: {}", getStreamName(), e.getMessage());
      lastErrorType.set("UnconfiguredException");
      consecutiveFailures.incrementAndGet();
    } catch (InterruptedException e) {
      log.info("[{}] Interrupted", getStreamName());
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (isRoutineStreamClosure(e)) {
        log.debug("[{}] Stream closed by server (routine reconnect)", getStreamName());
      } else {
        log.error("[{}] Error: {}", getStreamName(), e.getMessage(), e);
      }
      lastErrorType.set(e.getClass().getSimpleName());
      consecutiveFailures.incrementAndGet();
    } finally {
      totalReconnectionAttempts.incrementAndGet();
      lastDisconnectTime.set(Instant.now());
      isRunning.set(false);
      onStreamDisconnected();
      log.debug("[{}] Stream session ended", getStreamName());
    }
  }

  /** Hook called in the finally block of start(). Subclasses can clear observer refs here. */
  protected void onStreamDisconnected() {}

  // ---------------------------------------------------------------------------
  // Shared stream helpers
  // ---------------------------------------------------------------------------

  /** Record that the first server response was received and the connection is established. */
  protected void onConnectionEstablished() {
    connectionEstablishedTime.set(Instant.now());
    successfulReconnections.incrementAndGet();
    consecutiveFailures.set(0);
    lastErrorType.set(null);

    log.info(
        "[{}] Connection established (took {})",
        getStreamName(),
        Duration.between(reconnectionStartTime.get(), connectionEstablishedTime.get()));
  }

  /** Check if the connection is hung (no messages received within threshold). */
  protected void checkForHungConnection() throws HungConnectionException {
    Instant lastMsg = lastMessageTime.get();
    if (lastMsg == null) {
      lastMsg = connectionEstablishedTime.get();
    }
    if (lastMsg != null
        && lastMsg.isBefore(
            Instant.now().minus(HUNG_CONNECTION_THRESHOLD_SECONDS, ChronoUnit.SECONDS))) {
      throw new HungConnectionException(
          "No messages received since " + lastMsg + " - connection appears hung");
    }
  }

  /**
   * Wait for a stream latch to complete, periodically checking for hung connections. Returns when
   * the latch counts down, the timeout expires, or a hung connection is detected.
   */
  protected void awaitStreamWithHungDetection(CountDownLatch latch)
      throws InterruptedException, HungConnectionException {
    long startTime = System.currentTimeMillis();
    long maxDurationMs = TimeUnit.MINUTES.toMillis(STREAM_TIMEOUT_MINUTES);

    while ((System.currentTimeMillis() - startTime) < maxDurationMs) {
      if (latch.await(HUNG_CONNECTION_THRESHOLD_SECONDS, TimeUnit.SECONDS)) {
        return; // Stream completed
      }
      checkForHungConnection();
    }
  }

  /** Compute backoff delay with jitter based on consecutive failure count. */
  private long computeBackoffMs() {
    long failures = consecutiveFailures.get();
    if (failures <= 0) {
      return 0;
    }
    long delayMs = BACKOFF_BASE_MS * (1L << Math.min(failures - 1, 10));
    double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * BACKOFF_JITTER;
    return Math.min((long) (delayMs * jitter), BACKOFF_MAX_MS);
  }

  /**
   * Build base stream status map with connection tracking fields. Subclasses should call this and
   * add their own stream-specific counters.
   */
  protected Map<String, Object> buildStreamStatus() {
    Instant lastDisconnect = lastDisconnectTime.get();
    Instant connectionEstablished = connectionEstablishedTime.get();
    Instant reconnectStart = reconnectionStartTime.get();
    Instant lastMessage = lastMessageTime.get();

    Map<String, Object> status = new HashMap<>();
    status.put("isRunning", isRunning.get());
    status.put("totalReconnectionAttempts", totalReconnectionAttempts.get());
    status.put("successfulReconnections", successfulReconnections.get());
    status.put("consecutiveFailures", consecutiveFailures.get());
    status.put("lastDisconnectTime", lastDisconnect != null ? lastDisconnect.toString() : null);
    status.put(
        "connectionEstablishedTime",
        connectionEstablished != null ? connectionEstablished.toString() : null);
    status.put("reconnectionStartTime", reconnectStart != null ? reconnectStart.toString() : null);
    status.put("lastErrorType", lastErrorType.get());
    status.put("lastMessageTime", lastMessage != null ? lastMessage.toString() : null);
    return status;
  }

  // ---------------------------------------------------------------------------
  // Graceful shutdown
  // ---------------------------------------------------------------------------

  /** Override in subclasses to add stream-specific stop logic (e.g. closing observers). */
  public void stop() {
    doStop();
  }

  /** Shared shutdown logic: set running flag, shut down channel. */
  protected void doStop() {
    isRunning.set(false);
    shutdown();
  }

  // ---------------------------------------------------------------------------
  // Configuration and channel management
  // ---------------------------------------------------------------------------

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
          Thread.currentThread().interrupt();
        }

        sharedChannel = null;
      } else {
        log.debug("Tenant: {} - Shared channel is already null, shut down, or terminated.", tenant);
        if (sharedChannel != null && (sharedChannel.isShutdown() || sharedChannel.isTerminated())) {
          sharedChannel = null;
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private void forceShutdownSharedChannel() {
    log.info("forceShudown called, aquiring lock");
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
    long getChannelStartTime = System.currentTimeMillis();
    ManagedChannel localChannel = sharedChannel;
    if (localChannel == null || localChannel.isShutdown() || localChannel.isTerminated()) {

      long beforeLockAcquisition = System.currentTimeMillis();
      log.debug(
          "[LOCK-TIMING] getChannel attempting to acquire lock for tenant: {} at {}ms",
          tenant,
          beforeLockAcquisition);

      lock.lock();

      long afterLockAcquisition = System.currentTimeMillis();
      long lockWaitTime = afterLockAcquisition - beforeLockAcquisition;
      if (lockWaitTime > 100) {
        log.warn(
            "[LOCK-TIMING] getChannel lock acquired for tenant: {} after {}ms WAIT (potential contention!)",
            tenant,
            lockWaitTime);
      } else {
        log.debug(
            "[LOCK-TIMING] getChannel lock acquired for tenant: {} after {}ms",
            tenant,
            lockWaitTime);
      }
      try {
        localChannel = sharedChannel;

        var shutdown = localChannel == null || localChannel.isShutdown();
        var terminated = localChannel == null || localChannel.isTerminated();

        log.debug(
            "localChannel is null: {}, isShutdown: {}, isTerminated: {}",
            localChannel == null,
            shutdown,
            terminated);

        if (localChannel == null || localChannel.isShutdown() || localChannel.isTerminated()) {
          long beforeCreateChannel = System.currentTimeMillis();
          log.debug(
              "[LOCK-TIMING] getChannel creating new channel for tenant: {} at {}ms (lock held for {}ms so far)",
              tenant,
              beforeCreateChannel,
              beforeCreateChannel - afterLockAcquisition);

          localChannel = createNewChannel();

          long afterCreateChannel = System.currentTimeMillis();
          log.debug(
              "[LOCK-TIMING] getChannel channel created for tenant: {} at {}ms (createNewChannel took: {}ms, lock held total: {}ms)",
              tenant,
              afterCreateChannel,
              afterCreateChannel - beforeCreateChannel,
              afterCreateChannel - afterLockAcquisition);

          sharedChannel = localChannel;
        }
      } catch (Exception e) {
        log.error(
            "Tenant: {} - Error creating new static shared gRPC channel for config: {}",
            tenant,
            getConfig(),
            e);
        throw new UnconfiguredException("Error creating new static shared gRPC channel", e);
      } finally {
        long beforeUnlock = System.currentTimeMillis();
        lock.unlock();
        long afterUnlock = System.currentTimeMillis();
        long totalLockHeldTime = afterUnlock - afterLockAcquisition;
        log.debug(
            "[LOCK-TIMING] getChannel lock released for tenant: {} at {}ms (lock held for {}ms, total getChannel: {}ms)",
            tenant,
            afterUnlock,
            totalLockHeldTime,
            afterUnlock - getChannelStartTime);
      }
    } else {
      log.debug(
          "getChannel no new channel needed, returning localChannel null: {}, isShutdown: {}, isTerminated: {}",
          localChannel == null,
          localChannel.isShutdown(),
          localChannel.isTerminated());
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

      var hostname = getConfig().getApiHostname();
      var port = getConfig().getApiPort();
      var chan =
          Grpc.newChannelBuilderForAddress(hostname, port, channelCredentials)
              .keepAliveTime(32, TimeUnit.SECONDS)
              .keepAliveTimeout(30, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .idleTimeout(30, TimeUnit.MINUTES)
              .overrideAuthority("exile-proxy")
              .build();
      log.info("Managed Channel created for {}:{}", hostname, port);

      return chan;

    } catch (IOException e) {
      log.error("Tenant: {} - IOException during shared channel creation", tenant, e);
      throw new UnconfiguredException(
          "TCN Gate client configuration error during channel creation", e);
    } catch (UnconfiguredException e) {
      log.error("Tenant: {} - Configuration error during shared channel creation", tenant, e);
      throw e;
    } catch (Exception e) {
      log.error("Tenant: {} - Unexpected error during shared channel creation", tenant, e);
      throw new UnconfiguredException("Unexpected error configuring TCN Gate client channel", e);
    }
  }

  /**
   * Reset gRPC's internal connect backoff on the shared channel. This forces the name resolver to
   * immediately retry DNS resolution instead of waiting for its own exponential backoff timer.
   */
  protected void resetChannelBackoff() {
    ManagedChannel channel = sharedChannel;
    if (channel != null && !channel.isShutdown() && !channel.isTerminated()) {
      channel.resetConnectBackoff();
    }
  }

  /** Resets the gRPC channel after a connection failure. */
  protected void resetChannel() {
    log.info("Tenant: {} - Resetting static shared gRPC channel after connection failure.", tenant);
    shutdown();
  }

  /** Handle StatusRuntimeException, resetting channel on UNAVAILABLE. */
  protected boolean handleStatusRuntimeException(StatusRuntimeException e) {
    if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
      log.warn(
          "Tenant: {} - Connection unavailable, resetting channel: {}", tenant, e.getMessage());
      resetChannel();
      return true;
    }
    return false;
  }

  /** Get channel statistics for monitoring. */
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

  /** Exception for hung connection detection. */
  public static class HungConnectionException extends Exception {
    public HungConnectionException(String message) {
      super(message);
    }
  }

  private boolean isRoutineStreamClosure(Exception e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof StatusRuntimeException sre
          && sre.getStatus().getCode() == Status.Code.UNAVAILABLE
          && sre.getMessage() != null
          && sre.getMessage().contains("NO_ERROR")) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}
