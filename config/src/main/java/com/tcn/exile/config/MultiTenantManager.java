package com.tcn.exile.config;

import com.tcn.exile.ExileClient;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.StreamStatus;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages multiple {@link ExileClient} instances for multi-tenant deployments.
 *
 * <p>Polls a tenant provider for the current set of tenants and their configs. Creates clients for
 * new tenants, destroys clients for removed tenants, and updates clients when config changes.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var manager = MultiTenantManager.builder()
 *     .tenantProvider(() -> velosidyApi.listTenants())
 *     .clientFactory(tenantConfig -> ExileClient.builder()
 *         .config(tenantConfig)
 *         .jobHandler(new VelosidyJobHandler(tenantConfig))
 *         .eventHandler(new VelosidyEventHandler())
 *         .build())
 *     .pollInterval(Duration.ofSeconds(30))
 *     .build();
 *
 * manager.start();
 *
 * // Access a specific tenant's client.
 * manager.client("tenant-org-123").agents().listAgents(...);
 *
 * // List all active tenants.
 * manager.tenantIds();
 *
 * manager.stop();
 * }</pre>
 */
public final class MultiTenantManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MultiTenantManager.class);

  private final Supplier<Map<String, ExileConfig>> tenantProvider;
  private final Function<ExileConfig, ExileClient> clientFactory;
  private final long pollIntervalSeconds;

  private final ConcurrentHashMap<String, ExileClient> clients = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ExileConfig> configs = new ConcurrentHashMap<>();
  private volatile ScheduledExecutorService scheduler;

  private MultiTenantManager(Builder builder) {
    this.tenantProvider = builder.tenantProvider;
    this.clientFactory = builder.clientFactory;
    this.pollIntervalSeconds = builder.pollIntervalSeconds;
  }

  /** Start polling for tenants. */
  public void start() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "exile-tenant-poller");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleAtFixedRate(this::reconcile, 0, pollIntervalSeconds, TimeUnit.SECONDS);
    log.info("MultiTenantManager started (poll={}s)", pollIntervalSeconds);
  }

  /** Get the client for a specific tenant. Returns null if tenant not active. */
  public ExileClient client(String tenantId) {
    return clients.get(tenantId);
  }

  /** Returns all active tenant IDs. */
  public Set<String> tenantIds() {
    return Collections.unmodifiableSet(clients.keySet());
  }

  /** Returns stream status for all tenants. */
  public Map<String, StreamStatus> allStatuses() {
    var result = new ConcurrentHashMap<String, StreamStatus>();
    clients.forEach((id, client) -> result.put(id, client.streamStatus()));
    return result;
  }

  /** Stop all tenants and the polling loop. */
  public void stop() {
    log.info("Stopping MultiTenantManager ({} tenants)", clients.size());
    if (scheduler != null) scheduler.shutdownNow();
    clients.keySet().forEach(this::destroyTenant);
  }

  @Override
  public void close() {
    stop();
  }

  private void reconcile() {
    try {
      var desired = tenantProvider.get();
      if (desired == null) {
        log.warn("Tenant provider returned null");
        return;
      }

      // Remove tenants no longer in the desired set.
      for (var existingId : clients.keySet()) {
        if (!desired.containsKey(existingId)) {
          log.info("Tenant {} removed, destroying client", existingId);
          destroyTenant(existingId);
        }
      }

      // Create or update tenants.
      for (var entry : desired.entrySet()) {
        var tenantId = entry.getKey();
        var newConfig = entry.getValue();
        var existingConfig = configs.get(tenantId);

        if (existingConfig == null) {
          // New tenant.
          createTenant(tenantId, newConfig);
        } else if (!existingConfig.org().equals(newConfig.org())) {
          // Config changed (different org/certs).
          log.info("Tenant {} config changed, recreating client", tenantId);
          destroyTenant(tenantId);
          createTenant(tenantId, newConfig);
        }
      }
    } catch (Exception e) {
      log.warn("Tenant reconciliation failed: {}", e.getMessage());
    }
  }

  private void createTenant(String tenantId, ExileConfig config) {
    try {
      var client = clientFactory.apply(config);
      client.start();
      clients.put(tenantId, client);
      configs.put(tenantId, config);
      log.info("Created client for tenant {} (org={})", tenantId, config.org());
    } catch (Exception e) {
      log.error("Failed to create client for tenant {}: {}", tenantId, e.getMessage());
    }
  }

  private void destroyTenant(String tenantId) {
    var client = clients.remove(tenantId);
    configs.remove(tenantId);
    if (client != null) {
      try {
        client.close();
        log.info("Destroyed client for tenant {}", tenantId);
      } catch (Exception e) {
        log.warn("Error destroying client for tenant {}: {}", tenantId, e.getMessage());
      }
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Supplier<Map<String, ExileConfig>> tenantProvider;
    private Function<ExileConfig, ExileClient> clientFactory;
    private long pollIntervalSeconds = 30;

    private Builder() {}

    /**
     * Required. Provides the current set of tenants and their configs. Called on each poll cycle.
     * The map key is the tenant ID, value is the ExileConfig for that tenant.
     */
    public Builder tenantProvider(Supplier<Map<String, ExileConfig>> tenantProvider) {
      this.tenantProvider = Objects.requireNonNull(tenantProvider);
      return this;
    }

    /**
     * Required. Factory that creates an ExileClient for a given config. The factory should call
     * {@code ExileClient.builder()...build()} but NOT call {@code start()} — the manager handles
     * that.
     */
    public Builder clientFactory(Function<ExileConfig, ExileClient> clientFactory) {
      this.clientFactory = Objects.requireNonNull(clientFactory);
      return this;
    }

    /** How often to poll the tenant provider (seconds). Default: 30. */
    public Builder pollIntervalSeconds(long seconds) {
      this.pollIntervalSeconds = seconds;
      return this;
    }

    public MultiTenantManager build() {
      Objects.requireNonNull(tenantProvider, "tenantProvider required");
      Objects.requireNonNull(clientFactory, "clientFactory required");
      return new MultiTenantManager(this);
    }
  }
}
