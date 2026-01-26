package com.tcn.sati.infra.backend;

import java.util.List;
import java.util.Map;

/**
 * Unified interface for tenant backend operations.
 * Implementations handle tenant-specific data access (database or API).
 * 
 * Finvi: Connects to IRIS/Caché database via JDBC
 * Velosidy: Connects to Velosidy cloud via HTTP REST API
 */
public interface TenantBackendClient extends AutoCloseable {

    // ========== Pool Operations ==========
    
    /**
     * List all available pools for this tenant.
     */
    List<PoolInfo> listPools();
    
    /**
     * Get the status of a specific pool.
     */
    PoolStatus getPoolStatus(String poolId);
    
    /**
     * Get records from a pool (paginated).
     */
    List<PoolRecord> getPoolRecords(String poolId, int page);

    // ========== Event Handlers ==========
    
    /**
     * Handle a telephony result event from Exile.
     */
    void handleTelephonyResult(TelephonyResult result);
    
    /**
     * Handle a task event from Exile.
     */
    void handleTask(ExileTask task);
    
    /**
     * Handle an agent call event from Exile.
     */
    void handleAgentCall(AgentCall call);
    
    /**
     * Handle an agent response event from Exile.
     */
    void handleAgentResponse(AgentResponse response);

    // ========== Health ==========
    
    /**
     * Check if the backend connection is healthy.
     */
    boolean isConnected();

    // ========== DTOs ==========
    
    record PoolInfo(String id, String name, String status) {}
    record PoolStatus(String poolId, int totalRecords, int availableRecords, String status) {}
    record PoolRecord(String recordId, String poolId, Map<String, String> fields) {}
    record TelephonyResult(String callSid, String status, String result, Map<String, String> metadata) {}
    record ExileTask(String taskSid, String poolId, String recordId, String status) {}
    record AgentCall(String agentCallSid, String callSid, String userId, Map<String, Object> durations) {}
    record AgentResponse(String agentCallResponseSid, String callSid, String responseKey, String responseValue) {}
}
