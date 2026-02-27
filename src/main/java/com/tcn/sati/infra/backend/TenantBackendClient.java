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

    public static class PoolInfo {
        public String id;
        public String name;
        public String status;

        public PoolInfo() {}
        public PoolInfo(String id, String name, String status) {
            this.id = id;
            this.name = name;
            this.status = status;
        }
    }

    public static class PoolStatus {
        public String poolId;
        public int totalRecords;
        public int availableRecords;
        public String status;

        public PoolStatus() {}
        public PoolStatus(String poolId, int totalRecords, int availableRecords, String status) {
            this.poolId = poolId;
            this.totalRecords = totalRecords;
            this.availableRecords = availableRecords;
            this.status = status;
        }
    }

    public static class PoolRecord {
        public String recordId;
        public String poolId;
        public Map<String, String> fields;

        public PoolRecord() {}
        public PoolRecord(String recordId, String poolId, Map<String, String> fields) {
            this.recordId = recordId;
            this.poolId = poolId;
            this.fields = fields;
        }
    }

    public static class TelephonyResult {
        public String callSid;
        public String status;
        public String result;
        public Map<String, String> metadata;

        public TelephonyResult() {}
        public TelephonyResult(String callSid, String status, String result, Map<String, String> metadata) {
            this.callSid = callSid;
            this.status = status;
            this.result = result;
            this.metadata = metadata;
        }
    }

    public static class ExileTask {
        public String taskSid;
        public String poolId;
        public String recordId;
        public String status;

        public ExileTask() {}
        public ExileTask(String taskSid, String poolId, String recordId, String status) {
            this.taskSid = taskSid;
            this.poolId = poolId;
            this.recordId = recordId;
            this.status = status;
        }
    }

    public static class AgentCall {
        public String agentCallSid;
        public String callSid;
        public String userId;
        public Map<String, Object> durations;

        public AgentCall() {}
        public AgentCall(String agentCallSid, String callSid, String userId, Map<String, Object> durations) {
            this.agentCallSid = agentCallSid;
            this.callSid = callSid;
            this.userId = userId;
            this.durations = durations;
        }
    }

    public static class AgentResponse {
        public String agentCallResponseSid;
        public String callSid;
        public String responseKey;
        public String responseValue;

        public AgentResponse() {}
        public AgentResponse(String agentCallResponseSid, String callSid, String responseKey, String responseValue) {
            this.agentCallResponseSid = agentCallResponseSid;
            this.callSid = callSid;
            this.responseKey = responseKey;
            this.responseValue = responseValue;
        }
    }

    public static class TransferInstance {
        public String transferInstanceSid;
        public String callSid;
        public String status;

        public TransferInstance() {}
        public TransferInstance(String transferInstanceSid, String callSid, String status) {
            this.transferInstanceSid = transferInstanceSid;
            this.callSid = callSid;
            this.status = status;
        }
    }

    public static class CallRecording {
        public String recordingSid;
        public String callSid;
        public String status;

        public CallRecording() {}
        public CallRecording(String recordingSid, String callSid, String status) {
            this.recordingSid = recordingSid;
            this.callSid = callSid;
            this.status = status;
        }
    }

    // Job request/result DTOs

    public static class PopAccountRequest {
        public String recordId;
        public String userId;
        public String callSid;
        public String callType;
        public String callerId;
        public String phoneNumber;

        public PopAccountRequest() {}
        public PopAccountRequest(String recordId, String userId, String callSid, String callType, String callerId, String phoneNumber) {
            this.recordId = recordId;
            this.userId = userId;
            this.callSid = callSid;
            this.callType = callType;
            this.callerId = callerId;
            this.phoneNumber = phoneNumber;
        }
    }

    public static class PopAccountResult {
        public boolean success;

        public PopAccountResult() {}
        public PopAccountResult(boolean success) {
            this.success = success;
        }
    }

    public static class SearchRecordsRequest {
        public String lookupType;
        public String lookupValue;
        public Map<String, String> filters;

        public SearchRecordsRequest() {}
        public SearchRecordsRequest(String lookupType, String lookupValue, Map<String, String> filters) {
            this.lookupType = lookupType;
            this.lookupValue = lookupValue;
            this.filters = filters;
        }
    }

    public static class SearchResult {
        public String recordId;
        public String poolId;
        public Map<String, Object> fields;

        public SearchResult() {}
        public SearchResult(String recordId, String poolId, Map<String, Object> fields) {
            this.recordId = recordId;
            this.poolId = poolId;
            this.fields = fields;
        }
    }

    public static class ReadFieldsRequest {
        public String recordId;
        public String poolId;
        public List<String> fieldNames;
        public Map<String, String> filters;

        public ReadFieldsRequest() {}
        public ReadFieldsRequest(String recordId, String poolId, List<String> fieldNames, Map<String, String> filters) {
            this.recordId = recordId;
            this.poolId = poolId;
            this.fieldNames = fieldNames;
            this.filters = filters;
        }
    }

    public static class RecordField {
        public String recordId;
        public String poolId;
        public String fieldName;
        public String fieldValue;

        public RecordField() {}
        public RecordField(String recordId, String poolId, String fieldName, String fieldValue) {
            this.recordId = recordId;
            this.poolId = poolId;
            this.fieldName = fieldName;
            this.fieldValue = fieldValue;
        }
    }

    public static class WriteFieldsRequest {
        public String recordId;
        public Map<String, String> fields;
        public Map<String, String> filters;

        public WriteFieldsRequest() {}
        public WriteFieldsRequest(String recordId, Map<String, String> fields, Map<String, String> filters) {
            this.recordId = recordId;
            this.fields = fields;
            this.filters = filters;
        }
    }

    public static class CreatePaymentRequest {
        public String recordId;
        public String paymentId;
        public String paymentType;
        public String paymentAmount;
        public long paymentDateEpochSeconds;

        public CreatePaymentRequest() {}
        public CreatePaymentRequest(String recordId, String paymentId, String paymentType, String paymentAmount, long paymentDateEpochSeconds) {
            this.recordId = recordId;
            this.paymentId = paymentId;
            this.paymentType = paymentType;
            this.paymentAmount = paymentAmount;
            this.paymentDateEpochSeconds = paymentDateEpochSeconds;
        }
    }

    public static class ExecuteLogicRequest {
        public String logicBlockId;
        public String logicBlockParams;

        public ExecuteLogicRequest() {}
        public ExecuteLogicRequest(String logicBlockId, String logicBlockParams) {
            this.logicBlockId = logicBlockId;
            this.logicBlockParams = logicBlockParams;
        }
    }
}
