package com.tcn.exile.handler;

import com.tcn.exile.service.ConfigService;
import java.util.List;

/**
 * The integration point for CRM plugins. Implementations provide job handling, event handling, and
 * config validation.
 *
 * <p>Extend {@link PluginBase} for default implementations of common operations (logs, diagnostics,
 * info, shutdown, log level control). Only override the CRM-specific methods you need.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Config is polled from the gate
 *   <li>{@link #onConfig} is called — plugin validates and initializes resources
 *   <li>If {@code onConfig} returns {@code true}, the WorkStream opens
 *   <li>Jobs arrive → {@link JobHandler} methods are called
 *   <li>Events arrive → {@link EventHandler} methods are called
 * </ol>
 */
public interface Plugin extends JobHandler, EventHandler {

  /**
   * Called when the gate returns a new or changed config. The plugin should validate the config
   * payload and initialize its resources (database connections, HTTP clients, etc.).
   *
   * <p>Return {@code true} if the plugin is ready to handle work. The WorkStream opens only after
   * the first {@code true} return. Return {@code false} to reject the config — the poller will
   * retry on the next cycle.
   */
  boolean onConfig(ConfigService.ClientConfiguration config);

  /** Human-readable plugin name for diagnostics. */
  default String pluginName() {
    return getClass().getSimpleName();
  }

  /**
   * Upper bound on the number of in-flight work items the plugin is willing to accept right now.
   *
   * <p>The WorkStream uses this to drive credit-based flow control against the gate server: at any
   * moment, the number of outstanding credits granted to the server (Pulls sent minus WorkItems
   * received) is capped at {@code min(maxConcurrency, availableCapacity())}. When a plugin's
   * internal queue fills up, it can return a smaller number — or {@code 0} — and the server will
   * stop sending new items until capacity frees up.
   *
   * <p>Default: {@link Integer#MAX_VALUE}, i.e. unbounded — the pre-backpressure behavior. Plugins
   * that want backpressure (e.g. ones with a bounded internal work queue) should override.
   *
   * <p>This is polled frequently (on every WorkItem received and on a periodic timer); it must be
   * cheap and non-blocking.
   */
  default int availableCapacity() {
    return Integer.MAX_VALUE;
  }

  /**
   * Optional: declare structural resource limits the plugin operates under.
   *
   * <p>The adaptive concurrency controller uses these to:
   *
   * <ul>
   *   <li>Clamp its target concurrency to the minimum {@code hardMax} across all declared resources
   *       (conservative: assumes any job may need any resource).
   *   <li>Preemptively shed concurrency as {@code currentUsage} approaches {@code hardMax}, when
   *       {@code currentUsage} is reported.
   * </ul>
   *
   * <p>Default: empty list — no structural caps known, the controller uses only latency-based
   * signals.
   *
   * <p>Implementations should:
   *
   * <ul>
   *   <li>Return quickly (called from the controller recompute path, roughly every 25 job
   *       completions or every 500 ms).
   *   <li>Track {@code currentUsage} via an {@link java.util.concurrent.atomic.AtomicInteger}
   *       incremented on resource lease and decremented on release. Don't poll the underlying pool
   *       — that's usually expensive.
   * </ul>
   *
   * @return list of known resource limits; order insignificant; may be empty.
   */
  default List<ResourceLimit> resourceLimits() {
    return List.of();
  }
}
