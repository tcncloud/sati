package com.tcn.sati.infra.backend.jdbc;

import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.gate.GateClient.BackendConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDBC backend client with resilience features:
 * - Atomic datasource reference for thread-safe swapping
 * - Async datasource initialization (doesn't block caller)
 * - Connection failure tracking
 * - Automatic reinit on config changes
 */
public class JdbcBackendClient implements TenantBackendClient {
    private static final Logger log = LoggerFactory.getLogger(JdbcBackendClient.class);
    
    private final AtomicReference<HikariDataSource> dataSourceRef = new AtomicReference<>();
    private final AtomicInteger connectionFailureCount = new AtomicInteger(0);
    private final ExecutorService initExecutor;
    private final SatiConfig config;
    
    private volatile BackendConfig currentBackendConfig;
    private volatile boolean initializing = false;

    public JdbcBackendClient(SatiConfig config) {
        this.config = config;
        this.initExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jdbc-init");
            t.setDaemon(true);
            return t;
        });
        
        // If static config provided, use it
        if (config.backendUrl() != null && !config.backendUrl().isBlank()) {
            log.info("JdbcBackendClient initialized with static config");
            initializeDataSourceAsync(config.backendUrl(), config.backendUser(), config.backendPassword(), null);
        } else {
            log.info("JdbcBackendClient waiting for dynamic configuration from Gate...");
        }
    }

    /**
     * Called when Gate provides backend configuration.
     */
    public void onBackendConfigReceived(BackendConfig backendConfig) {
        log.info("Received backend configuration from Gate: {}", backendConfig);
        
        // Check if config actually changed
        if (backendConfig.equals(currentBackendConfig)) {
            log.debug("Backend config unchanged, skipping reinitialization");
            return;
        }
        
        currentBackendConfig = backendConfig;
        connectionFailureCount.set(0);  // Reset failure count on new config
        
        // Close existing datasource
        closeDataSource();
        
        // Initialize new datasource asynchronously
        String jdbcUrl = backendConfig.getEffectiveJdbcUrl();
        initializeDataSourceAsync(jdbcUrl, backendConfig.databaseUsername, backendConfig.databasePassword, backendConfig);
    }

    /**
     * Initialize datasource asynchronously to avoid blocking the caller.
     */
    private void initializeDataSourceAsync(String jdbcUrl, String user, String password, BackendConfig backendConfig) {
        if (initializing) {
            log.debug("Already initializing datasource, skipping");
            return;
        }
        
        initializing = true;
        initExecutor.execute(() -> {
            try {
                initializeDataSource(jdbcUrl, user, password, backendConfig);
            } finally {
                initializing = false;
            }
        });
    }

    private void initializeDataSource(String jdbcUrl, String user, String password, BackendConfig backendConfig) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            log.warn("Cannot initialize datasource: JDBC URL is empty");
            return;
        }

        try {
            log.info("Initializing JDBC connection to: {}", jdbcUrl);
            
            HikariConfig hc = new HikariConfig();
            
            // Determine if IRIS or Cache based on URL
            boolean isIris = jdbcUrl.toUpperCase().contains("IRIS");
            
            if (isIris) {
                // Use DataSourceClassName for IRIS
                hc.setDataSourceClassName("com.intersystems.jdbc.IRISDataSource");
                
                String host = backendConfig != null ? backendConfig.databaseHost : "localhost";
                String port = backendConfig != null ? backendConfig.databasePort : "1972";
                String dbName = backendConfig != null ? backendConfig.databaseName : "USER";
                
                hc.addDataSourceProperty("serverName", host);
                try {
                    hc.addDataSourceProperty("portNumber", Integer.parseInt(port));
                } catch (NumberFormatException e) {
                    hc.addDataSourceProperty("portNumber", 1972);
                }
                hc.addDataSourceProperty("databaseName", dbName);
                hc.addDataSourceProperty("user", user);
                hc.addDataSourceProperty("password", password);
                
                // TLS configuration
                if (backendConfig != null && Boolean.TRUE.equals(backendConfig.useTls)) {
                    hc.addDataSourceProperty("SSLConfigurationName", "IRISTLS13");
                    hc.addDataSourceProperty("connectionSecurityLevel", 10);
                    log.info("TLS enabled for IRIS connection");
                }
                
                log.info("Configured IRISDataSource: host={}, port={}, db={}", host, port, dbName);
            } else {
                // Use driver class for Cache
                hc.setDriverClassName("com.intersys.jdbc.CacheDriver");
                hc.setJdbcUrl(jdbcUrl);
            }
            
            hc.setUsername(user);
            hc.setPassword(password);
            
            // Pool settings - resilience focused
            int maxConnections = (backendConfig != null && backendConfig.maxConnections != null) 
                ? backendConfig.maxConnections : 10;
            hc.setMaximumPoolSize(maxConnections);
            hc.setMinimumIdle(2);
            
            // Connection timeouts
            hc.setConnectionTimeout(10_000);       // 10s to get connection from pool
            hc.setValidationTimeout(5_000);        // 5s for connection validation
            
            // Keep connections healthy
            hc.setKeepaliveTime(60_000);           // 60s keepalive ping
            hc.setIdleTimeout(120_000);            // 2 min idle timeout
            hc.setMaxLifetime(360_000);            // 6 min max lifetime
            
            // Connection validation
            hc.setConnectionTestQuery("SELECT 1");
            
            // Leak detection (useful for debugging)
            hc.setLeakDetectionThreshold(90_000); // 90s
            
            HikariDataSource ds = new HikariDataSource(hc);
            
            // Test connection
            try (Connection testConn = ds.getConnection()) {
                if (testConn.isValid(5)) {
                    log.info("✅ Database connection successful!");
                    
                    // Swap in new datasource
                    HikariDataSource oldDs = dataSourceRef.getAndSet(ds);
                    if (oldDs != null && !oldDs.isClosed()) {
                        oldDs.close();
                    }
                    
                    connectionFailureCount.set(0);
                } else {
                    log.error("Database connection test failed");
                    connectionFailureCount.incrementAndGet();
                    ds.close();
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize datasource: {}", e.getMessage(), e);
            connectionFailureCount.incrementAndGet();
        }
    }

    private void closeDataSource() {
        HikariDataSource ds = dataSourceRef.getAndSet(null);
        if (ds != null && !ds.isClosed()) {
            log.info("Closing existing datasource");
            ds.close();
        }
    }

    @Override
    public List<PoolInfo> listPools() {
        HikariDataSource ds = getDataSourceOrThrow();
        
        List<PoolInfo> pools = new ArrayList<>();
        String sql = "SELECT PoolID, PoolName, Status FROM Sample.Pool";
        
        try (Connection con = ds.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            
            while (rs.next()) {
                pools.add(new PoolInfo(
                    rs.getString("PoolID"),
                    rs.getString("PoolName"),
                    rs.getString("Status")
                ));
            }
        } catch (SQLException e) {
            log.error("Failed to list pools", e);
            throw new RuntimeException("Database error", e);
        }
        
        return pools;
    }

    @Override
    public PoolStatus getPoolStatus(String poolId) {
        HikariDataSource ds = getDataSourceOrThrow();
        
        String sql = "SELECT PoolID, TotalRecords, AvailableRecords, Status FROM Sample.PoolStatus WHERE PoolID = ?";
        
        try (Connection con = ds.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            
            ps.setString(1, poolId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PoolStatus(
                        rs.getString("PoolID"),
                        rs.getInt("TotalRecords"),
                        rs.getInt("AvailableRecords"),
                        rs.getString("Status")
                    );
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get pool status for {}", poolId, e);
            throw new RuntimeException("Database error", e);
        }
        
        return new PoolStatus(poolId, 0, 0, "UNKNOWN");
    }

    @Override
    public List<PoolRecord> getPoolRecords(String poolId, int page) {
        HikariDataSource ds = getDataSourceOrThrow();
        
        List<PoolRecord> records = new ArrayList<>();
        int pageSize = 100;
        int offset = page * pageSize;
        
        String sql = "SELECT TOP ? RecordID, PoolID, FirstName, LastName, Phone FROM Sample.PoolRecord WHERE PoolID = ? ORDER BY RecordID OFFSET ?";
        
        try (Connection con = ds.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            
            ps.setInt(1, pageSize);
            ps.setString(2, poolId);
            ps.setInt(3, offset);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> fields = new HashMap<>();
                    fields.put("firstName", rs.getString("FirstName"));
                    fields.put("lastName", rs.getString("LastName"));
                    fields.put("phone", rs.getString("Phone"));
                    
                    records.add(new PoolRecord(
                        rs.getString("RecordID"),
                        rs.getString("PoolID"),
                        fields
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get records for pool {}", poolId, e);
            throw new RuntimeException("Database error", e);
        }
        
        return records;
    }

    @Override
    public void handleTelephonyResult(TelephonyResult result) {
        HikariDataSource ds = getDataSourceOrThrow();
        log.info("Handling telephony result for callSid: {}", result.callSid());
    }

    @Override
    public void handleTask(ExileTask task) {
        HikariDataSource ds = getDataSourceOrThrow();
        log.info("Handling task: {} for pool: {}", task.taskSid(), task.poolId());
    }

    @Override
    public void handleAgentCall(AgentCall call) {
        log.info("Handling agent call: {} for callSid: {}", call.agentCallSid(), call.callSid());
    }

    @Override
    public void handleAgentResponse(AgentResponse response) {
        log.info("Handling agent response: {} key: {}", response.agentCallResponseSid(), response.responseKey());
    }

    @Override
    public boolean isConnected() {
        HikariDataSource ds = dataSourceRef.get();
        if (ds == null || ds.isClosed()) {
            return false;
        }
        
        try (Connection con = ds.getConnection()) {
            return con.isValid(5);
        } catch (SQLException e) {
            log.warn("Connection check failed", e);
            connectionFailureCount.incrementAndGet();
            return false;
        }
    }

    /**
     * Get the datasource or throw if not configured.
     */
    private HikariDataSource getDataSourceOrThrow() {
        HikariDataSource ds = dataSourceRef.get();
        if (ds == null) {
            throw new IllegalStateException("Database not configured - waiting for config from Gate");
        }
        return ds;
    }

    /**
     * Get connection failure count for monitoring.
     */
    public int getConnectionFailureCount() {
        return connectionFailureCount.get();
    }

    @Override
    public void close() {
        log.info("Closing JdbcBackendClient");
        
        initExecutor.shutdown();
        try {
            if (!initExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                initExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            initExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        closeDataSource();
    }
}
