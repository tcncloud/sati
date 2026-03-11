package com.tcn.sati.core.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for tenant operations.
 * Prevents cascading failures by temporarily blocking requests to failing tenants.
 *
 * States:
 * - CLOSED: normal operation, requests flow through
 * - OPEN: too many failures, requests blocked for a cooldown period
 * - HALF_OPEN: testing if tenant has recovered, limited requests allowed
 *
 * Ported from old velosidy's TenantCircuitBreaker.
 */
public class TenantCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(TenantCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String tenantId;
    private final int failureThreshold;
    private final Duration openTimeout;
    private final Duration halfOpenTimeout;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicReference<LocalDateTime> lastFailureTime = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> stateChangeTime = new AtomicReference<>(LocalDateTime.now());

    public TenantCircuitBreaker(String tenantId) {
        this(tenantId, 5, Duration.ofMinutes(2), Duration.ofSeconds(30));
    }

    public TenantCircuitBreaker(String tenantId, int failureThreshold,
            Duration openTimeout, Duration halfOpenTimeout) {
        this.tenantId = tenantId;
        this.failureThreshold = failureThreshold;
        this.openTimeout = openTimeout;
        this.halfOpenTimeout = halfOpenTimeout;
    }

    /**
     * Check if the circuit breaker allows the request to proceed.
     */
    public boolean canExecute() {
        State currentState = state.get();
        LocalDateTime now = LocalDateTime.now();

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                if (now.isAfter(stateChangeTime.get().plus(openTimeout))) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        stateChangeTime.set(now);
                        log.info("Circuit breaker for tenant '{}': OPEN -> HALF_OPEN", tenantId);
                    }
                    return true;
                }
                return false;

            case HALF_OPEN:
                if (now.isAfter(stateChangeTime.get().plus(halfOpenTimeout))) {
                    if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                        stateChangeTime.set(now);
                        log.warn("Circuit breaker for tenant '{}': HALF_OPEN timeout, back to OPEN", tenantId);
                    }
                    return false;
                }
                return true;

            default:
                return false;
        }
    }

    /**
     * Record a successful operation.
     */
    public void recordSuccess() {
        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                failureCount.set(0);
                stateChangeTime.set(LocalDateTime.now());
                log.info("Circuit breaker for tenant '{}': recovered -> CLOSED", tenantId);
            }
        } else if (currentState == State.CLOSED) {
            int current = failureCount.get();
            if (current > 0) {
                failureCount.set(Math.max(0, current - 1));
            }
        }
    }

    /**
     * Record a failed operation.
     */
    public void recordFailure(Throwable throwable) {
        lastFailureTime.set(LocalDateTime.now());
        int failures = failureCount.incrementAndGet();
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                stateChangeTime.set(LocalDateTime.now());
                log.warn("Circuit breaker for tenant '{}': failed during recovery -> OPEN. Error: {}",
                        tenantId, throwable.getMessage());
            }
        } else if (currentState == State.CLOSED && failures >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                stateChangeTime.set(LocalDateTime.now());
                log.error("Circuit breaker for tenant '{}': OPENED after {} failures. Error: {}",
                        tenantId, failures, throwable.getMessage());
            }
        }
    }

    public State getState() { return state.get(); }
    public int getFailureCount() { return failureCount.get(); }
    public LocalDateTime getLastFailureTime() { return lastFailureTime.get(); }
}
