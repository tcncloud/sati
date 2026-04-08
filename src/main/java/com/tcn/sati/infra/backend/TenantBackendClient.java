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
     * @return RPC value from stored procedure result, or null if none
     */
    String handleTelephonyResult(TelephonyResult result);

    /**
     * Handle a task event from Exile.
     */
    void handleTask(ExileTask task);

    /**
     * Handle an agent call event from Exile.
     * @return RPC value from stored procedure result, or null if none
     */
    String handleAgentCall(AgentCall call);

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
        public int recordCount;

        public PoolInfo() {}
        public PoolInfo(String id, String name, String status) {
            this.id = id;
            this.name = name;
            this.status = status;
        }
    }

    public static class PoolStatus {
        public String poolId;
        public String description;
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
        public long callSid;
        public String callType;
        public String status;
        public String result;
        public String callerId;
        public String phoneNumber;
        public String recordId;
        public String poolId;
        public long deliveryLength;
        public long linkbackLength;
        public long clientSid;
        public String orgId;
        public String internalKey;
        public String createTime;
        public String updateTime;
        public String startTime;
        public String endTime;
        public String taskWaitingUntil;
        public long oldCallSid;
        public String oldCallType;

        public TelephonyResult() {}
    }

    public static class ExileTask {
        public String taskSid;
        public String taskGroupSid;
        public String poolId;
        public String recordId;
        public String status;
        public long attempts;
        public long clientSid;
        public String orgId;
        public String createTime;
        public String updateTime;

        public ExileTask() {}
    }

    public static class AgentCall {
        public long agentCallSid;
        public long callSid;
        public String callType;
        public String userId;
        public String partnerAgentId;
        public String orgId;
        public String internalKey;
        public long talkDuration;
        public long callWaitDuration;
        public long wrapUpDuration;
        public long pauseDuration;
        public long transferDuration;
        public long manualDuration;
        public long previewDuration;
        public long holdDuration;
        public long agentWaitDuration;
        public long suspendedDuration;
        public long externalTransferDuration;
        public String createTime;
        public String updateTime;

        public AgentCall() {}
    }

    public static class AgentResponse {
        public long agentCallResponseSid;
        public long callSid;
        public String callType;
        public String responseKey;
        public String responseValue;
        public long clientSid;
        public String userId;
        public long agentSid;
        public String partnerAgentId;
        public String orgId;
        public String internalKey;
        public String createTime;
        public String updateTime;

        public AgentResponse() {}
    }

    public static class TransferInstance {
        public long clientSid;
        public String transferInstanceId;
        public String orgId;
        public String transferResult;
        public String transferType;
        // Source
        public String sourceCallSid;
        public String sourceCallType;
        public String sourceUserId;
        public String sourcePartnerAgentId;
        public long sourceConversationId;
        public long sourceSessionSid;
        // Destination (type = "agent", "call", or "phone"; null if absent)
        public String destinationType;
        public String destinationPartnerAgentId;
        public String destinationUserId;
        public long destinationSessionSid;
        public String destinationCallSid;
        public String destinationCallType;
        public long destinationConversationId;
        public String destinationPhoneNumber;
        public java.util.Map<String, Boolean> destinationSkills;
        // Durations
        public long durationMicroseconds;
        public long externalDurationMicroseconds;
        public long pendingDurationMicroseconds;
        public boolean startAsPending;
        public boolean startedAsConference;
        // Timestamps
        public String createTime;
        public String updateTime;
        public String transferPendingStartTime;
        public String transferStartTime;
        public String transferEndTime;
        public String transferExternalEndTime;

        public TransferInstance() {}
    }

    public static class CallRecording {
        public String recordingId;
        public long callSid;
        public String callType;
        public String recordingType;
        public String orgId;
        public long durationSeconds;
        public String startTime;

        public CallRecording() {}
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
