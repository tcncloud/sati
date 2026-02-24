package com.tcn.sati;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.TenantBackendClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the SatiApp builder wiring and TenantContext lifecycle.
 * 
 * These are integration-style tests that verify real object construction
 * without needing a Gate server or database. Uses a stub backend client.
 */
class SatiAppTest {

    /**
     * A minimal stub backend that does nothing but returns empty data.
     * This is what a third party would create to test their integration.
     */
    static class StubBackendClient implements TenantBackendClient {
        boolean connected = true;

        @Override
        public List<PoolInfo> listPools() {
            return List.of();
        }

        @Override
        public PoolStatus getPoolStatus(String poolId) {
            return new PoolStatus(poolId, 0, 0, "ok");
        }

        @Override
        public List<PoolRecord> getPoolRecords(String poolId, int page) {
            return List.of();
        }

        @Override
        public void handleTelephonyResult(TelephonyResult result) {
        }

        @Override
        public void handleTask(ExileTask task) {
        }

        @Override
        public void handleAgentCall(AgentCall call) {
        }

        @Override
        public void handleAgentResponse(AgentResponse response) {
        }

        @Override
        public void handleTransferInstance(TransferInstance transfer) {
        }

        @Override
        public void handleCallRecording(CallRecording recording) {
        }

        @Override
        public PopAccountResult popAccount(PopAccountRequest r) {
            return new PopAccountResult(true);
        }

        @Override
        public List<SearchResult> searchRecords(SearchRecordsRequest r) {
            return List.of();
        }

        @Override
        public List<RecordField> readFields(ReadFieldsRequest r) {
            return List.of();
        }

        @Override
        public void writeFields(WriteFieldsRequest r) {
        }

        @Override
        public void createPayment(CreatePaymentRequest r) {
        }

        @Override
        public String executeLogic(ExecuteLogicRequest r) {
            return "{}";
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            connected = false;
        }
    }

    /**
     * Builder with a stub backend and no Gate config starts without crashing.
     * Gate features (gRPC, job stream, event stream) are disabled but the app
     * boots.
     */
    @Test
    void builderStartsWithStubBackendAndNoGate() {
        SatiConfig config = SatiConfig.builder()
                .org("test-org")
                .tenant("test-tenant")
                .build();

        // Gate is not configured (no certs), so gRPC should be skipped
        assertFalse(config.isGateConfigured());

        SatiApp app = SatiApp.builder()
                .config(config)
                .backendClient(new StubBackendClient())
                .appName("Test App")
                .build();

        assertNotNull(app);

        // Start on random port
        try {
            app.start(0); // Port 0 = pick any available
            assertNotNull(app.getTenantContext());
            assertTrue(app.getTenantContext().isRunning());
            assertNotNull(app.getTenantContext().getBackendClient());
            // Gate should be null since no certs provided
            assertNull(app.getTenantContext().getGateClient());
        } finally {
            try {
                app.getApp().stop();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * SatiConfig builder creates config with expected values.
     */
    @Test
    void configBuilderSetsAllFields() {
        SatiConfig config = SatiConfig.builder()
                .apiHostname("gate.example.com")
                .apiPort(443)
                .rootCert("root-cert-pem")
                .publicCert("client-cert-pem")
                .privateKey("private-key-pem")
                .org("my-org")
                .tenant("my-tenant")
                .backendUrl("jdbc:postgresql://localhost/mydb")
                .backendUser("admin")
                .backendPassword("secret")
                .build();

        assertEquals("gate.example.com", config.apiHostname());
        assertEquals(443, config.apiPort());
        assertTrue(config.isGateConfigured());
        assertTrue(config.isBackendConfigured());
        assertEquals("my-org", config.org());
        assertEquals("jdbc:postgresql://localhost/mydb", config.backendUrl());
    }

    /**
     * Config without certs reports gate as not configured.
     */
    @Test
    void configWithoutCertsIsNotGateConfigured() {
        SatiConfig config = SatiConfig.builder()
                .apiHostname("gate.example.com")
                .apiPort(443)
                .org("my-org")
                .build();

        assertFalse(config.isGateConfigured());
    }

    /**
     * JDBC backend without a custom client throws an error
     * (because we can't auto-create a JDBC client without knowing the driver).
     */
    @Test
    void jdbcWithoutCustomClientThrows() {
        SatiConfig config = SatiConfig.builder()
                .org("test-org")
                .tenant("test-tenant")
                .build();

        SatiApp app = SatiApp.builder()
                .config(config)
                .backendType(BackendType.JDBC)
                .appName("Test App")
                .build();

        assertThrows(RuntimeException.class, () -> app.start(0));
    }
}
