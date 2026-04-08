package com.tcn.exile;

import java.time.Instant;

/**
 * Snapshot of the bidirectional work stream's current state.
 *
 * <p>Obtain via {@link ExileClient#streamStatus()}.
 */
public record StreamStatus(
    /** Current connection phase. */
    Phase phase,
    /** Server-assigned client ID (set after REGISTERED, null before). */
    String clientId,
    /** When the current stream connection was established (null if not connected). */
    Instant connectedSince,
    /** When the last disconnect occurred (null if never disconnected). */
    Instant lastDisconnect,
    /** Last error message (null if no error). */
    String lastError,
    /** Number of work items currently being processed. */
    int inflight,
    /** Total work items completed (results + acks) since start. */
    long completedTotal,
    /** Total work items that failed since start. */
    long failedTotal,
    /** Total stream reconnection attempts since start. */
    long reconnectAttempts) {

  public enum Phase {
    /** Client created but start() not yet called. */
    IDLE,
    /** Connecting to the gate server. */
    CONNECTING,
    /** Stream open, Register sent, waiting for Registered response. */
    REGISTERING,
    /** Registered and actively pulling/processing work. */
    ACTIVE,
    /** Stream disconnected, waiting to reconnect (backoff). */
    RECONNECTING,
    /** Client has been closed. */
    CLOSED
  }

  /** True if the stream is connected and processing work. */
  public boolean isHealthy() {
    return phase == Phase.ACTIVE;
  }
}
