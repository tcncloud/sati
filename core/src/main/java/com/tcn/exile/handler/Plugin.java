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
}
