package com.tcn.sati.core.service;

import com.tcn.sati.infra.gate.GateClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent service — contains the business logic for agent operations.
 * Routes delegate here. Subclass to override behavior.
 *
 * Default implementation calls GateClient gRPC directly.
 */
public class AgentService {
    protected final GateClient gate;

    public AgentService(GateClient gate) {
        this.gate = gate;
    }

    // ========== Business Logic (override these) ==========

    public List<Map<String, Object>> listAgents(Boolean loggedIn, String state) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.ListAgentsRequest.newBuilder();
        if (loggedIn != null) {
            reqBuilder.setLoggedIn(loggedIn);
        }
        if (state != null && !state.isBlank()) {
            String normalized = state.trim().toUpperCase();
            if (!normalized.startsWith("AGENT_STATE_")) {
                normalized = "AGENT_STATE_" + normalized;
            }
            reqBuilder.setState(build.buf.gen.tcnapi.exile.gate.v2.AgentState.valueOf(normalized));
        }

        var results = gate.listAgents(reqBuilder.build());
        List<Map<String, Object>> agents = new ArrayList<>();
        while (results.hasNext()) {
            var resp = results.next();
            var a = resp.getAgent();
            agents.add(agentToMap(a));
        }
        return agents;
    }

    public Map<String, Object> upsertAgent(String username, String firstName, String lastName,
            String partnerAgentId, String password) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.UpsertAgentRequest.newBuilder()
                .setUsername(username);
        if (firstName != null)
            reqBuilder.setFirstName(firstName);
        if (lastName != null)
            reqBuilder.setLastName(lastName);
        if (partnerAgentId != null)
            reqBuilder.setPartnerAgentId(partnerAgentId);
        if (password != null)
            reqBuilder.setPassword(password);

        var resp = gate.upsertAgent(reqBuilder.build());
        return agentToMap(resp.getAgent());
    }

    public Map<String, Object> getAgentState(String agentId) {
        var resp = gate.getAgentStatus(
                build.buf.gen.tcnapi.exile.gate.v2.GetAgentStatusRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());

        var result = new LinkedHashMap<String, Object>();
        result.put("partnerAgentId", resp.getPartnerAgentId());
        result.put("agentState", resp.getAgentState().name().replace("AGENT_STATE_", ""));
        result.put("currentSessionId", resp.getCurrentSessionId());
        result.put("agentIsMuted", resp.getAgentIsMuted());
        if (resp.hasConnectedParty()) {
            var cp = resp.getConnectedParty();
            var cpMap = new HashMap<String, Object>();
            cpMap.put("callSid", cp.getCallSid());
            cpMap.put("callType", cp.getCallType().name().replace("CALL_TYPE_", "").toLowerCase());
            cpMap.put("isInbound", cp.getIsInbound());
            result.put("connectedParty", cpMap);
        }
        return result;
    }

    public Map<String, Object> updateAgentState(String agentId, String state, String reason) {
        String normalized = state.startsWith("AGENT_STATE_") ? state : "AGENT_STATE_" + state;
        var agentState = build.buf.gen.tcnapi.exile.gate.v2.AgentState.valueOf(normalized);

        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.UpdateAgentStatusRequest.newBuilder()
                .setPartnerAgentId(agentId)
                .setNewState(agentState);
        if (reason != null)
            reqBuilder.setReason(reason);

        gate.updateAgentStatus(reqBuilder.build());
        return Map.of("success", true);
    }

    public Map<String, Object> dial(String agentId, String phoneNumber, String callerId,
            String poolId, String recordId, String rulesetName,
            Boolean skipComplianceChecks, Boolean recordCall) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.DialRequest.newBuilder()
                .setPartnerAgentId(agentId)
                .setPhoneNumber(phoneNumber);
        if (callerId != null)
            reqBuilder.setCallerId(com.google.protobuf.StringValue.of(callerId));
        if (poolId != null)
            reqBuilder.setPoolId(com.google.protobuf.StringValue.of(poolId));
        if (recordId != null)
            reqBuilder.setRecordId(com.google.protobuf.StringValue.of(recordId));
        if (rulesetName != null)
            reqBuilder.setRulesetName(com.google.protobuf.StringValue.of(rulesetName));
        if (skipComplianceChecks != null)
            reqBuilder.setSkipComplianceChecks(skipComplianceChecks);
        if (recordCall != null)
            reqBuilder.setRecordCall(com.google.protobuf.BoolValue.of(recordCall));

        var resp = gate.dial(reqBuilder.build());
        var result = new HashMap<String, Object>();
        result.put("phoneNumber", resp.getPhoneNumber());
        result.put("callerId", resp.getCallerId());
        result.put("callSid", resp.getCallSid());
        result.put("callType", resp.getCallType().name().replace("CALL_TYPE_", "").toLowerCase());
        result.put("orgId", resp.getOrgId());
        result.put("partnerAgentId", resp.getPartnerAgentId());
        result.put("attempted", resp.getAttempted());
        result.put("status", resp.getStatus());
        result.put("callerSid", resp.getCallerSid());
        return result;
    }

    public Map<String, Object> getRecordingStatus(String agentId) {
        var resp = gate.getRecordingStatus(
                build.buf.gen.tcnapi.exile.gate.v2.GetRecordingStatusRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("isRecording", resp.getIsRecording());
    }

    public Map<String, Object> startRecording(String agentId) {
        gate.startCallRecording(
                build.buf.gen.tcnapi.exile.gate.v2.StartCallRecordingRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("isRecording", true);
    }

    public Map<String, Object> stopRecording(String agentId) {
        gate.stopCallRecording(
                build.buf.gen.tcnapi.exile.gate.v2.StopCallRecordingRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("isRecording", false);
    }

    public Object listPauseCodes(String agentId) {
        var resp = gate.listHuntGroupPauseCodes(
                build.buf.gen.tcnapi.exile.gate.v2.ListHuntGroupPauseCodesRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return resp.getPauseCodesList();
    }

    public Map<String, Object> simpleHold(String agentId) {
        gate.putCallOnSimpleHold(
                build.buf.gen.tcnapi.exile.gate.v2.PutCallOnSimpleHoldRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }

    public Map<String, Object> simpleUnhold(String agentId) {
        gate.takeCallOffSimpleHold(
                build.buf.gen.tcnapi.exile.gate.v2.TakeCallOffSimpleHoldRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }

    public Map<String, Object> mute(String agentId) {
        gate.muteAgent(
                build.buf.gen.tcnapi.exile.gate.v2.MuteAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }

    public Map<String, Object> unmute(String agentId) {
        gate.unmuteAgent(
                build.buf.gen.tcnapi.exile.gate.v2.UnmuteAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }

    public Map<String, Object> addCallResponse(String agentId, String callSid,
            Long currentSessionId, String key, String value) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.AddAgentCallResponseRequest.newBuilder()
                .setPartnerAgentId(agentId)
                .setCallSid(callSid)
                .setKey(key)
                .setValue(value);
        if (currentSessionId != null)
            reqBuilder.setCurrentSessionId(currentSessionId);

        gate.addAgentCallResponse(reqBuilder.build());
        return Map.of("success", true);
    }

    // ========== Helpers ==========

    protected Map<String, Object> agentToMap(build.buf.gen.tcnapi.exile.gate.v2.Agent a) {
        var map = new HashMap<String, Object>();
        map.put("userId", a.getUserId());
        map.put("orgId", a.getOrgId());
        map.put("partnerAgentId", a.getPartnerAgentId());
        map.put("username", a.getUsername());
        map.put("firstName", a.getFirstName());
        map.put("lastName", a.getLastName());
        map.put("currentSessionId", a.getCurrentSessionId());
        map.put("agentState", a.getAgentState().name().replace("AGENT_STATE_", ""));
        map.put("isLoggedIn", a.getIsLoggedIn());
        return map;
    }
}
