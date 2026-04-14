package com.tcn.exile.handler;

import com.tcn.exile.service.ConfigService;

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
}
