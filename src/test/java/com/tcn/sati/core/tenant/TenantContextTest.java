package com.tcn.sati.core.tenant;

import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.TenantBackendClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the TenantContext lifecycle — starting, health checks, and shutdown.
 * Uses a stub backend and no Gate connection.
 */
class TenantContextTest {

    static class StubBackendClient implements TenantBackendClient {
        boolean connected = true;
        int listPoolsCalls = 0;

        @Override
        public List<PoolInfo> listPools() {
            listPoolsCalls++;
            return List.of(new PoolInfo("p1", "Pool 1", "active"));
        }

        @Override
        public PoolStatus getPoolStatus(String poolId) {
            return new PoolStatus(poolId, 10, 5, "ok");
        }

        @Override
        public List<PoolRecord> getPoolRecords(String poolId, int page) {
            return List.of();
        }

        @Override
        public String handleTelephonyResult(TelephonyResult result) {
            return null;
        }

        @Override
        public void handleTask(ExileTask task) {
        }

        @Override
        public String handleAgentCall(AgentCall call) {
            return null;
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
     * TenantContext starts and reports running with a stub backend.
     */
    @Test
    void startsWithStubBackend() {
        SatiConfig config = SatiConfig.builder()
                .org("test-org")
                .tenant("test-tenant")
                .build();

        StubBackendClient backend = new StubBackendClient();
        TenantContext ctx = new TenantContext("test-tenant", config, backend);

        assertFalse(ctx.isRunning());

        ctx.start();

        assertTrue(ctx.isRunning());
        assertSame(backend, ctx.getBackendClient());
        // No Gate, so these should be null
        assertNull(ctx.getGateClient());
        assertNull(ctx.getJobQueueClient());
        assertNull(ctx.getEventStreamClient());

        ctx.close();

        assertFalse(ctx.isRunning());
    }

    /**
     * Health check reflects backend connection state.
     */
    @Test
    void healthReflectsBackendState() {
        SatiConfig config = SatiConfig.builder()
                .org("test-org")
                .tenant("test-tenant")
                .build();

        StubBackendClient backend = new StubBackendClient();
        TenantContext ctx = new TenantContext("test-tenant", config, backend);
        ctx.start();

        assertTrue(ctx.isHealthy());

        backend.connected = false;
        assertFalse(ctx.isHealthy());

        ctx.close();
    }

    /**
     * getStatus returns a snapshot of tenant state.
     */
    @Test
    void statusReportsCorrectly() {
        SatiConfig config = SatiConfig.builder()
                .org("test-org")
                .tenant("test-tenant")
                .build();

        StubBackendClient backend = new StubBackendClient();
        TenantContext ctx = new TenantContext("test-tenant", config, backend);
        ctx.start();

        var status = ctx.getStatus();
        assertEquals("test-tenant", status.tenantKey());
        assertTrue(status.running());
        assertTrue(status.backendConnected());
        assertFalse(status.gateConnected()); // No gate
        assertNotNull(status.createdAt());

        ctx.close();
    }

    /**
     * Double-start is a no-op.
     */
    @Test
    void doubleStartIsNoop() {
        SatiConfig config = SatiConfig.builder()
                .org("test-org")
                .tenant("test-tenant")
                .build();

        TenantContext ctx = new TenantContext("test-tenant", config, new StubBackendClient());
        ctx.start();
        ctx.start(); // Should not throw
        assertTrue(ctx.isRunning());
        ctx.close();
    }
}
