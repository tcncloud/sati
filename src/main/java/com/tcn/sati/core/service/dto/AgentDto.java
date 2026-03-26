package com.tcn.sati.core.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.openapi.OpenApiByFields;

import java.util.List;

/**
 * DTOs for AgentService requests and responses.
 * All classes are plain Java with public fields — extensible by subclassing.
 * Jackson serializes them to JSON automatically.
 *
 * JSON keys use @JsonProperty for backward compatibility with legacy clients.
 */
public class AgentDto {

    /** Shared connected party info (snake_case JSON keys). */
    @OpenApiByFields
    public static class ConnectedParty {
        @JsonProperty("call_sid")
        public String callSid;

        @JsonProperty("call_type")
        public String callType;

        @JsonProperty("inbound")
        public boolean inbound;
    }

    /** Agent listing info — returned by listAgents and upsertAgent. */
    @OpenApiByFields
    public static class AgentInfo {
        public String userId;
        public String orgId;
        public String partnerAgentId;
        public String username;
        public String firstName;
        public String lastName;
        public Long currentSessionId;
        public String agentState; // full enum e.g. "AGENT_STATE_READY"
        public Boolean isLoggedIn;
        public Boolean isRecording;
        public Boolean agentIsMuted;
        public ConnectedParty connectedParty;
    }

    /** Agent state with optional connected party — returned by getAgentState. */
    @OpenApiByFields
    public static class AgentStateInfo {
        @JsonProperty("user_id")
        public String userId;

        @JsonProperty("state")
        public String agentState; // full enum e.g. "AGENT_STATE_READY"

        @JsonProperty("current_session_id")
        public Long currentSessionId; // nullable Long

        @JsonProperty("connected_party")
        public ConnectedParty connectedParty;

        @JsonProperty("agent_is_muted")
        public boolean agentIsMuted;

        @JsonProperty("is_recording")
        public boolean isRecording;
    }

    /** Dial response — returned by dial. */
    @OpenApiByFields
    public static class DialResult {
        @JsonProperty("phone_number")
        public String phoneNumber;

        @JsonProperty("caller_id")
        public String callerId;

        @JsonProperty("call_sid")
        public Long callSid;

        @JsonProperty("call_type")
        public String callType;

        @JsonProperty("org_id")
        public String orgId;

        @JsonProperty("partner_agent_id")
        public String partnerAgentId;

        @JsonProperty("attempted")
        public Boolean attempted;

        @JsonProperty("status")
        public String status;

        @JsonProperty("caller_sid")
        public Long callerSid;
    }

    /**
     * Recording status — returned by
     * getRecordingStatus/startRecording/stopRecording.
     */
    @OpenApiByFields
    public static class RecordingStatus {
        @JsonProperty("recording")
        public boolean recording;

        public RecordingStatus() {
        }

        public RecordingStatus(boolean recording) {
            this.recording = recording;
        }
    }

    // ========== Request DTOs ==========

    /** Request for listing agents. */
    @OpenApiByFields
    public static class ListAgentsRequest {
        public Boolean loggedIn;
        public String state;
        public boolean fetchRecordingStatus;
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
        public String callSid;
        public String callType;
        public Long currentSessionId;
        public String key;
        public String value;
    }

    /** Request body for setState when pausing. */
    @OpenApiByFields
    public static class PauseCodeReason {
        public String reason;
    }
}
