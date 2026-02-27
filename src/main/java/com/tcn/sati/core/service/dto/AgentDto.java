package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

import java.util.List;

/**
 * DTOs for AgentService requests and responses.
 * All classes are plain Java with public fields — extensible by subclassing.
 * Jackson serializes them to JSON automatically.
 */
public class AgentDto {

    /** Agent listing info — returned by listAgents and upsertAgent. */
    @OpenApiByFields
    public static class AgentInfo {
        public String userId;
        public String orgId;
        public String partnerAgentId;
        public String username;
        public String firstName;
        public String lastName;
        public long currentSessionId;
        public String agentState;
        public boolean isLoggedIn;
    }

    /** Agent state with optional connected party — returned by getAgentState. */
    @OpenApiByFields
    public static class AgentStateInfo {
        public String partnerAgentId;
        public String agentState;
        public long currentSessionId;
        public boolean agentIsMuted;
        public ConnectedParty connectedParty;

        @OpenApiByFields
        public static class ConnectedParty {
            public String callSid;
            public String callType;
            public boolean isInbound;
        }
    }

    /** Dial response — returned by dial. */
    @OpenApiByFields
    public static class DialResult {
        public String phoneNumber;
        public String callerId;
        public String callSid;
        public String callType;
        public String orgId;
        public String partnerAgentId;
        public boolean attempted;
        public String status;
        public String callerSid;
    }

    /**
     * Recording status — returned by
     * getRecordingStatus/startRecording/stopRecording.
     */
    @OpenApiByFields
    public static class RecordingStatus {
        public boolean isRecording;

        public RecordingStatus() {
        }

        public RecordingStatus(boolean isRecording) {
            this.isRecording = isRecording;
        }
    }

    // ========== Request DTOs ==========

    /** Request for listing agents. */
    @OpenApiByFields
    public static class ListAgentsRequest {
        public Boolean loggedIn;
        public String state;
    }

    /** Request for creating/updating an agent. */
    @OpenApiByFields
    public static class UpsertAgentRequest {
        public String username;
        public String firstName;
        public String lastName;
        public String partnerAgentId;
        public String password;
    }

    /** Request for dialing. */
    @OpenApiByFields
    public static class DialRequest {
        public String partnerAgentId;
        public String phoneNumber;
        public String callerId;
        public String poolId;
        public String recordId;
        public String rulesetName;
        public Boolean skipComplianceChecks;
        public Boolean recordCall;
    }

    /** Request for adding a call response. */
    @OpenApiByFields
    public static class CallResponseRequest {
        public String partnerAgentId;
        public String callSid;
        public Long currentSessionId;
        public String key;
        public String value;
    }
}
