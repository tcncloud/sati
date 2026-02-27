package com.tcn.sati.infra.backend.jdbc;

import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.gate.GateClient.BackendConfig;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the JdbcBackendClient template method pattern using H2 (in-memory DB).
 * 
 * This proves:
 * 1. Subclasses can plug in their own driver/SQL
 * 2. onBackendConfigReceived triggers connection init
 * 3. Pool operations work through the template
 * 
 * This is exactly what a third party would go through when building
 * their own JdbcBackendClient implementation.
 */
class JdbcBackendClientTest {

    private H2BackendClient client;

    /**
     * An H2-backed "tenant" backend — same pattern a third party would follow
     * when implementing their own JdbcBackendClient.
     */
    static class H2BackendClient extends JdbcBackendClient {

        H2BackendClient() {
            super(SatiConfig.builder().build());
        }

        @Override
        protected void configureDataSource(HikariConfig hc, BackendConfig backendConfig) {
            hc.setDriverClassName("org.h2.Driver");
            hc.setJdbcUrl(buildJdbcUrl(backendConfig));
        }

        @Override
        protected String buildJdbcUrl(BackendConfig backendConfig) {
            return "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
        }

        @Override
        protected String getListPoolsSql() {
            return "SELECT 'pool-1' AS PoolID, 'Test Pool' AS PoolName, 'active' AS Status";
        }

        @Override
        protected String getPoolStatusSql() {
            return "SELECT ? AS PoolID, 10 AS TotalRecords, 5 AS AvailableRecords, 'active' AS Status";
        }

        @Override
        protected String getPoolRecordsSql() {
            // JdbcBackendClient sets: 1=pageSize(int), 2=poolId(string), 3=offset(int)
            // H2 doesn't support parameterized LIMIT, so just consume all params in WHERE
            return "SELECT 'rec-1' AS RecordID, 'pool-1' AS PoolID, 'John' AS FirstName, 'Doe' AS LastName, '555-1234' AS Phone FROM (VALUES(1)) WHERE ? > 0 AND ? IS NOT NULL AND ? >= 0";
        }

        @Override
        protected String getTelephonyResultSql() {
            return "SELECT 1";
        }

        @Override
        protected String getTaskSql() {
            return "SELECT 1";
        }

        @Override
        protected String getAgentCallSql() {
            return "SELECT 1";
        }

        @Override
        protected String getAgentResponseSql() {
            return "SELECT 1";
        }

        @Override
        protected String getTransferInstanceSql() {
            return "SELECT 1";
        }

        @Override
        protected String getCallRecordingSql() {
            return "SELECT 1";
        }

        @Override
        protected String getPopAccountSql() {
            return "SELECT 1";
        }

        @Override
        protected String getSearchRecordsSql() {
            return "SELECT 1";
        }

        @Override
        protected String getReadFieldsSql() {
            return "SELECT 1";
        }

        @Override
        protected String getWriteFieldsSql() {
            return "SELECT 1";
        }

        @Override
        protected String getCreatePaymentSql() {
            return "SELECT 1";
        }

        @Override
        protected String getExecuteLogicSql() {
            return "SELECT 1";
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Before receiving config from Gate, backend reports not connected.
     */
    @Test
    void notConnectedBeforeConfig() {
        client = new H2BackendClient();
        assertFalse(client.isConnected());
    }

    /**
     * After receiving config, datasource initializes and backend is connected.
     */
    @Test
    void connectedAfterConfigReceived() throws Exception {
        client = new H2BackendClient();

        // Simulate Gate sending backend config
        BackendConfig cfg = new BackendConfig();
        cfg.databaseHost = "localhost";
        cfg.databasePort = "9092";
        cfg.databaseName = "testdb";
        cfg.databaseUsername = "sa";
        cfg.databasePassword = "";

        client.onBackendConfigReceived(cfg);

        // Wait a bit for async init
        Thread.sleep(2000);

        assertTrue(client.isConnected());
    }

    /**
     * listPools returns data from the SQL provided by the subclass.
     */
    @Test
    void listPoolsReturnsData() throws Exception {
        client = new H2BackendClient();
        initClient(client);

        var pools = client.listPools();

        assertFalse(pools.isEmpty());
        assertEquals("pool-1", pools.get(0).id);
        assertEquals("Test Pool", pools.get(0).name);
    }

    /**
     * getPoolStatus returns the status from the subclass SQL.
     */
    @Test
    void getPoolStatusReturnsData() throws Exception {
        client = new H2BackendClient();
        initClient(client);

        var status = client.getPoolStatus("pool-1");

        assertEquals("pool-1", status.poolId);
        assertEquals(10, status.totalRecords);
        assertEquals(5, status.availableRecords);
    }

    /**
     * getPoolRecords returns paginated records.
     */
    @Test
    void getPoolRecordsReturnsData() throws Exception {
        client = new H2BackendClient();
        initClient(client);

        var records = client.getPoolRecords("pool-1", 0);

        assertFalse(records.isEmpty());
        assertEquals("rec-1", records.get(0).recordId);
    }

    /**
     * Helper to init the client with H2 config and wait for connection.
     */
    private void initClient(H2BackendClient c) throws Exception {
        BackendConfig cfg = new BackendConfig();
        cfg.databaseHost = "localhost";
        cfg.databasePort = "9092";
        cfg.databaseName = "testdb";
        cfg.databaseUsername = "sa";
        cfg.databasePassword = "";
        c.onBackendConfigReceived(cfg);
        Thread.sleep(2000);
        assertTrue(c.isConnected(), "H2 backend should be connected after init");
    }
}
