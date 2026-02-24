package com.tcn.sati.infra.backend;

import java.util.List;
import java.util.Map;

/**
 * Unified interface for tenant backend operations.
 * Implementations handle tenant-specific data access (database or API).
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

    /**
     * Handle a transfer instance event from Exile.
     */
    void handleTransferInstance(TransferInstance transfer);

    /**
     * Handle a call recording event from Exile.
     */
    void handleCallRecording(CallRecording recording);

    // ========== Job Handlers ==========

    /**
     * Pop (screen-pop) an account for an agent.
     * Called when an inbound/outbound call connects to an agent.
     */
    PopAccountResult popAccount(PopAccountRequest request);

    /**
     * Search records by lookup type and value (e.g., phone number lookup).
     */
    List<SearchResult> searchRecords(SearchRecordsRequest request);

    /**
     * Read specific fields from a record.
     */
    List<RecordField> readFields(ReadFieldsRequest request);

    /**
     * Write/update fields on a record.
     */
    void writeFields(WriteFieldsRequest request);

    /**
     * Create a payment record.
     */
    void createPayment(CreatePaymentRequest request);

    /**
     * Execute a custom logic block.
     */
    String executeLogic(ExecuteLogicRequest request);

    // ========== Health ==========

    /**
     * Check if the backend connection is healthy.
     */
    boolean isConnected();

    // ========== DTOs ==========

    record PoolInfo(String id, String name, String status) {
    }

    record PoolStatus(String poolId, int totalRecords, int availableRecords, String status) {
    }

    record PoolRecord(String recordId, String poolId, Map<String, String> fields) {
    }

    record TelephonyResult(String callSid, String status, String result, Map<String, String> metadata) {
    }

    record ExileTask(String taskSid, String poolId, String recordId, String status) {
    }

    record AgentCall(String agentCallSid, String callSid, String userId, Map<String, Object> durations) {
    }

    record AgentResponse(String agentCallResponseSid, String callSid, String responseKey, String responseValue) {
    }

    record TransferInstance(String transferInstanceSid, String callSid, String status) {
    }

    record CallRecording(String recordingSid, String callSid, String status) {
    }

    // Job request/result DTOs

    record PopAccountRequest(String recordId, String userId, String callSid, String callType,
            String callerId, String phoneNumber) {
    }

    record PopAccountResult(boolean success) {
    }

    record SearchRecordsRequest(String lookupType, String lookupValue,
            Map<String, String> filters) {
    }

    record SearchResult(String recordId, String poolId, Map<String, Object> fields) {
    }

    record ReadFieldsRequest(String recordId, String poolId, List<String> fieldNames,
            Map<String, String> filters) {
    }

    record RecordField(String recordId, String poolId, String fieldName, String fieldValue) {
    }

    record WriteFieldsRequest(String recordId, Map<String, String> fields,
            Map<String, String> filters) {
    }

    record CreatePaymentRequest(String recordId, String paymentId, String paymentType,
            String paymentAmount, long paymentDateEpochSeconds) {
    }

    record ExecuteLogicRequest(String logicBlockId, String logicBlockParams) {
    }
}
