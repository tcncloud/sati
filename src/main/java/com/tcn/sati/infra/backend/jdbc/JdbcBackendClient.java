package com.tcn.sati.infra.backend.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.gate.GateClient.BackendConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract JDBC backend client with common infrastructure.
 * 
 * Handles:
 * - HikariCP connection pool management
 * - Async initialization
 * - Health checks
 * - BackendConfig integration
 * 
 * Subclasses provide:
 * - Driver/DataSource configuration
 * - SQL statements for stored procedures
 */
public abstract class JdbcBackendClient implements TenantBackendClient {
    private static final Logger log = LoggerFactory.getLogger(JdbcBackendClient.class);

    private final AtomicReference<HikariDataSource> dataSourceRef = new AtomicReference<>();
    private final AtomicInteger connectionFailureCount = new AtomicInteger(0);
    private final ExecutorService initExecutor;
    protected final SatiConfig config;
    protected final ObjectMapper objectMapper;

    private volatile BackendConfig currentBackendConfig;
    private volatile boolean initializing = false;

    protected JdbcBackendClient(SatiConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.initExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jdbc-backend-init");
            t.setDaemon(true);
            return t;
        });

        // If static config provided, use it
        if (config.backendUrl() != null && !config.backendUrl().isBlank()) {
            log.info("{} initialized with static config", getClass().getSimpleName());
            initializeDataSourceAsync(config.backendUrl(), config.backendUser(), config.backendPassword(), null);
        } else {
            log.info("{} waiting for dynamic configuration from Gate...", getClass().getSimpleName());
        }
    }

    // ========== Abstract Methods - Subclasses Provide ==========

    /**
     * Configure the HikariCP datasource with driver-specific settings.
     * Called during initialization to set driver class, connection properties, etc.
     */
    protected abstract void configureDataSource(HikariConfig hikariConfig, BackendConfig backendConfig);

    /**
     * SQL to list all pools. Expected columns: PoolID, PoolName, Status
     */
    protected abstract String getListPoolsSql();

    /**
     * SQL to get pool status. Expected columns: PoolID, TotalRecords,
     * AvailableRecords, Status
     * Use ? as placeholder for poolId parameter.
     */
    protected abstract String getPoolStatusSql();

    /**
     * SQL to get pool records (paginated).
     * Parameters: pageSize (int), poolId (String), offset (int)
     * Expected columns: RecordID, PoolID, FirstName, LastName, Phone
     */
    protected abstract String getPoolRecordsSql();

    /**
     * SQL to handle telephony result. Single ? parameter for JSON payload.
     */
    protected abstract String getTelephonyResultSql();

    /**
     * SQL to handle task. Single ? parameter for JSON payload.
     */
    protected abstract String getTaskSql();

    /**
     * SQL to handle agent call. Single ? parameter for JSON payload.
     */
    protected abstract String getAgentCallSql();

    /**
     * SQL to handle agent response. Single ? parameter for JSON payload.
     */
    protected abstract String getAgentResponseSql();

    /**
     * SQL to handle transfer instance. Single ? parameter for JSON payload.
     */
    protected abstract String getTransferInstanceSql();

    /**
     * SQL to handle call recording. Single ? parameter for JSON payload.
     */
    protected abstract String getCallRecordingSql();

    /**
     * Build a JDBC URL from the BackendConfig components.
     * Called when BackendConfig.getEffectiveJdbcUrl() returns null.
     * 
     * @param backendConfig The config with host, port, name, type fields
     * @return A complete JDBC URL for your database
     */
    protected abstract String buildJdbcUrl(BackendConfig backendConfig);

    // ========== Connection Management ==========

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
        connectionFailureCount.set(0);

        // Close existing datasource
        closeDataSource();

        // Initialize new datasource asynchronously
        // Use explicit URL if provided, otherwise let subclass build it
        String jdbcUrl = backendConfig.getEffectiveJdbcUrl();
        if (jdbcUrl == null) {
            jdbcUrl = buildJdbcUrl(backendConfig);
        }
        initializeDataSourceAsync(jdbcUrl, backendConfig.databaseUsername, backendConfig.databasePassword,
                backendConfig);
    }

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

            // Let subclass configure driver-specific settings
            configureDataSource(hc, backendConfig);

            hc.setUsername(user);
            hc.setPassword(password);

            // Pool settings
            int maxConnections = (backendConfig != null && backendConfig.maxConnections != null)
                    ? backendConfig.maxConnections
                    : 10;
            hc.setMaximumPoolSize(maxConnections);
            hc.setMinimumIdle(2);

            // Connection timeouts
            hc.setConnectionTimeout(10_000);
            hc.setValidationTimeout(5_000);
            hc.setKeepaliveTime(60_000);
            hc.setMaxLifetime(360_000);

            hc.setConnectionTestQuery("SELECT 1");
            hc.setLeakDetectionThreshold(90_000);

            HikariDataSource ds = new HikariDataSource(hc);

            // Test connection
            try (Connection testConn = ds.getConnection()) {
                if (testConn.isValid(5)) {
                    log.info("✅ Database connection successful!");

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

    protected HikariDataSource getDataSourceOrThrow() {
        HikariDataSource ds = dataSourceRef.get();
        if (ds == null) {
            throw new IllegalStateException("Database not configured - waiting for config from Gate");
        }
        return ds;
    }

    protected Connection getConnection() throws SQLException {
        return getDataSourceOrThrow().getConnection();
    }

    // ========== Pool Operations ==========

    @Override
    public List<PoolInfo> listPools() {
        List<PoolInfo> pools = new ArrayList<>();

        try (Connection con = getConnection();
                var ps = con.prepareStatement(getListPoolsSql());
                var rs = ps.executeQuery()) {

            while (rs.next()) {
                pools.add(new PoolInfo(
                        rs.getString("PoolID"),
                        rs.getString("PoolName"),
                        rs.getString("Status")));
            }
        } catch (SQLException e) {
            log.error("Failed to list pools", e);
            throw new RuntimeException("Database error", e);
        }

        return pools;
    }

    @Override
    public PoolStatus getPoolStatus(String poolId) {
        try (Connection con = getConnection();
                var ps = con.prepareStatement(getPoolStatusSql())) {

            ps.setString(1, poolId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PoolStatus(
                            rs.getString("PoolID"),
                            rs.getInt("TotalRecords"),
                            rs.getInt("AvailableRecords"),
                            rs.getString("Status"));
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
        List<PoolRecord> records = new ArrayList<>();
        int pageSize = 100;
        int offset = page * pageSize;

        try (Connection con = getConnection();
                var ps = con.prepareStatement(getPoolRecordsSql())) {

            ps.setInt(1, pageSize);
            ps.setString(2, poolId);
            ps.setInt(3, offset);

            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> fields = new HashMap<>();
                    fields.put("firstName", rs.getString("FirstName"));
                    fields.put("lastName", rs.getString("LastName"));
                    fields.put("phone", rs.getString("Phone"));

                    records.add(new PoolRecord(
                            rs.getString("RecordID"),
                            rs.getString("PoolID"),
                            fields));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get records for pool {}", poolId, e);
            throw new RuntimeException("Database error", e);
        }

        return records;
    }

    // ========== Event Handlers ==========

    @Override
    public void handleTelephonyResult(TelephonyResult result) {
        log.info("Handling telephony result for callSid: {}", result.callSid());

        try (Connection con = getConnection();
                var stmt = con.prepareStatement(getTelephonyResultSql())) {

            Map<String, Object> payload = new HashMap<>();
            payload.put("call_sid", result.callSid());
            payload.put("status", result.status());
            payload.put("result", result.result());
            payload.putAll(result.metadata());

            stmt.setString(1, objectMapper.writeValueAsString(payload));
            stmt.execute();

            // Process result sets from stored procedure
            processStoredProcedureResults(stmt);

            log.info("Telephony result processed for callSid: {}", result.callSid());

        } catch (Exception e) {
            log.error("Failed to handle telephony result for callSid: {}", result.callSid(), e);
            throw new RuntimeException("Stored procedure error", e);
        }
    }

    @Override
    public void handleTask(ExileTask task) {
        log.info("Handling task: {} for pool: {}", task.taskSid(), task.poolId());

        try (Connection con = getConnection();
                var stmt = con.prepareStatement(getTaskSql())) {

            Map<String, Object> payload = new HashMap<>();
            payload.put("task_sid", task.taskSid());
            payload.put("pool_id", task.poolId());
            payload.put("record_id", task.recordId());
            payload.put("status", task.status());

            stmt.setString(1, objectMapper.writeValueAsString(payload));
            stmt.execute();

            log.info("Task processed: {}", task.taskSid());

        } catch (Exception e) {
            log.error("Failed to handle task: {}", task.taskSid(), e);
            throw new RuntimeException("Stored procedure error", e);
        }
    }

    @Override
    public void handleAgentCall(AgentCall call) {
        log.info("Handling agent call: {} for callSid: {}", call.agentCallSid(), call.callSid());

        try (Connection con = getConnection();
                var stmt = con.prepareStatement(getAgentCallSql())) {

            Map<String, Object> payload = new HashMap<>();
            payload.put("agent_call_sid", call.agentCallSid());
            payload.put("call_sid", call.callSid());
            payload.put("user_id", call.userId());
            payload.putAll(call.durations());

            stmt.setString(1, objectMapper.writeValueAsString(payload));
            stmt.execute();

            log.info("Agent call processed: {}", call.agentCallSid());

        } catch (Exception e) {
            log.error("Failed to handle agent call: {}", call.agentCallSid(), e);
            throw new RuntimeException("Stored procedure error", e);
        }
    }

    @Override
    public void handleAgentResponse(AgentResponse response) {
        log.info("Handling agent response: {} key: {}", response.agentCallResponseSid(), response.responseKey());

        try (Connection con = getConnection();
                var stmt = con.prepareStatement(getAgentResponseSql())) {

            Map<String, Object> payload = new HashMap<>();
            payload.put("agent_call_response_sid", response.agentCallResponseSid());
            payload.put("call_sid", response.callSid());
            payload.put("response_key", response.responseKey());
            payload.put("response_value", response.responseValue());

            stmt.setString(1, objectMapper.writeValueAsString(payload));
            stmt.execute();

            log.info("Agent response processed: {}", response.agentCallResponseSid());

        } catch (Exception e) {
            log.error("Failed to handle agent response: {}", response.agentCallResponseSid(), e);
            throw new RuntimeException("Stored procedure error", e);
        }
    }

    @Override
    public void handleTransferInstance(TransferInstance transfer) {
        log.info("Handling transfer instance: {}", transfer.transferInstanceSid());

        try (Connection con = getConnection();
                var stmt = con.prepareStatement(getTransferInstanceSql())) {

            Map<String, Object> payload = new HashMap<>();
            payload.put("transfer_instance_sid", transfer.transferInstanceSid());
            payload.put("call_sid", transfer.callSid());
            payload.put("status", transfer.status());

            stmt.setString(1, objectMapper.writeValueAsString(payload));
            stmt.execute();

            log.info("Transfer instance processed: {}", transfer.transferInstanceSid());

        } catch (Exception e) {
            log.error("Failed to handle transfer instance: {}", transfer.transferInstanceSid(), e);
            throw new RuntimeException("Stored procedure error", e);
        }
    }

    @Override
    public void handleCallRecording(CallRecording recording) {
        log.info("Handling call recording: {} for callSid: {}", recording.recordingSid(), recording.callSid());

        try (Connection con = getConnection();
                var stmt = con.prepareStatement(getCallRecordingSql())) {

            Map<String, Object> payload = new HashMap<>();
            payload.put("recording_sid", recording.recordingSid());
            payload.put("call_sid", recording.callSid());
            payload.put("status", recording.status());

            stmt.setString(1, objectMapper.writeValueAsString(payload));
            stmt.execute();

            log.info("Call recording processed: {}", recording.recordingSid());

        } catch (Exception e) {
            log.error("Failed to handle call recording: {}", recording.recordingSid(), e);
            throw new RuntimeException("Stored procedure error", e);
        }
    }

    /**
     * Process any result sets returned by a stored procedure.
     * Override in subclasses if custom handling is needed.
     */
    protected void processStoredProcedureResults(java.sql.PreparedStatement stmt) throws SQLException {
        do {
            try (var rs = stmt.getResultSet()) {
                if (rs != null) {
                    while (rs.next()) {
                        var rsmd = rs.getMetaData();
                        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                            log.debug("SP Result: {} = {}", rsmd.getColumnName(i), rs.getString(i));
                        }
                    }
                }
            }
        } while (stmt.getMoreResults() || stmt.getUpdateCount() != -1);
    }

    // ========== Health & Utilities ==========

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

    public int getConnectionFailureCount() {
        return connectionFailureCount.get();
    }

    @Override
    public void close() {
        log.info("Closing {}", getClass().getSimpleName());

        initExecutor.shutdown();
        try {
            if (!initExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                initExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            initExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        closeDataSource();
    }
}
