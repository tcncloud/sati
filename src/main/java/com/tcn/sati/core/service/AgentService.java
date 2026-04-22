package com.tcn.sati.core.service;

import com.tcn.sati.core.service.dto.AgentDto;
import com.tcn.sati.core.service.dto.AgentDto.AgentInfo;
import com.tcn.sati.core.service.dto.AgentDto.AgentStateInfo;
import com.tcn.sati.core.service.dto.AgentDto.CallResponseRequest;
import com.tcn.sati.core.service.dto.AgentDto.DialRequest;
import com.tcn.sati.core.service.dto.AgentDto.DialResult;
import com.tcn.sati.core.service.dto.AgentDto.ListAgentsRequest;
import com.tcn.sati.core.service.dto.AgentDto.RecordingStatus;
import com.tcn.sati.core.service.dto.AgentDto.UpsertAgentRequest;
import com.tcn.sati.core.service.dto.SuccessResult;
import com.tcn.sati.infra.gate.GateClient;

import java.util.ArrayList;
import java.util.List;

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

    public List<AgentInfo> listAgents(ListAgentsRequest request) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v3.ListAgentsRequest.newBuilder();
        if (request.loggedIn != null) {
            reqBuilder.setLoggedIn(request.loggedIn);
        }
        if (request.state != null && !request.state.isBlank()) {
            String normalized = request.state.trim().toUpperCase();
            if (!normalized.startsWith("AGENT_STATE_")) {
                normalized = "AGENT_STATE_" + normalized;
            }
            reqBuilder.setState(build.buf.gen.tcnapi.exile.gate.v3.AgentState.valueOf(normalized));
        }
        reqBuilder.setIncludeRecordingStatus(request.fetchRecordingStatus);

        var resp = gate.listAgents(reqBuilder.build());
        List<AgentInfo> agents = new ArrayList<>();
        for (var a : resp.getAgentsList()) {
            agents.add(toAgentInfo(a, request.fetchRecordingStatus));
        }
        return agents;
    }

    public AgentInfo upsertAgent(UpsertAgentRequest request) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v3.UpsertAgentRequest.newBuilder()
                .setUsername(request.username);
        if (request.firstName != null)
            reqBuilder.setFirstName(request.firstName);
        if (request.lastName != null)
            reqBuilder.setLastName(request.lastName);
        if (request.partnerAgentId != null)
            reqBuilder.setPartnerAgentId(request.partnerAgentId);

        var resp = gate.upsertAgent(reqBuilder.build());

        // v3 moved password to a separate SetAgentCredentials RPC
        if (request.password != null && !request.password.isBlank()) {
            gate.setAgentCredentials(
                    build.buf.gen.tcnapi.exile.gate.v3.SetAgentCredentialsRequest.newBuilder()
                            .setPartnerAgentId(resp.getAgent().getPartnerAgentId())
                            .setPassword(request.password)
                            .build());
        }

        return toAgentInfo(resp.getAgent(), false);
    }

    public AgentStateInfo getAgentState(String agentId) {
        var resp = gate.getAgentStatus(
                build.buf.gen.tcnapi.exile.gate.v3.GetAgentStatusRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());

        var result = new AgentStateInfo();
        result.userId = resp.getPartnerAgentId();
        result.agentState = resp.getAgentState().name();
        String sid = resp.getCurrentSessionId();
        result.currentSessionId = (sid != null && !sid.isEmpty()) ? Long.parseLong(sid) : null;
        result.agentIsMuted = resp.getIsMuted();
        result.isRecording = resp.getIsRecording();
        if (resp.hasConnectedParty()) {
            var cp = resp.getConnectedParty();
            var cpDto = new AgentDto.ConnectedParty();
            cpDto.callSid = String.valueOf(cp.getCallSid());
            cpDto.callType = cp.getCallType().name().replace("CALL_TYPE_", "").toLowerCase();
            cpDto.inbound = cp.getIsInbound();
            result.connectedParty = cpDto;
        }
        return result;
    }

    public SuccessResult updateAgentState(String agentId, String state, String reason) {
        String normalized = state.startsWith("AGENT_STATE_") ? state : "AGENT_STATE_" + state;
        var agentState = build.buf.gen.tcnapi.exile.gate.v3.AgentState.valueOf(normalized);

        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v3.UpdateAgentStatusRequest.newBuilder()
                .setPartnerAgentId(agentId)
                .setNewState(agentState);
        if (reason != null)
            reqBuilder.setReason(reason);

        gate.updateAgentStatus(reqBuilder.build());
        return new SuccessResult();
    }

    public DialResult dial(String agentId, DialRequest request) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v3.DialRequest.newBuilder()
                .setPartnerAgentId(agentId)
                .setPhoneNumber(request.phoneNumber);
        if (request.callerId != null)
            reqBuilder.setCallerId(request.callerId);
        if (request.poolId != null)
            reqBuilder.setPoolId(request.poolId);
        if (request.recordId != null)
            reqBuilder.setRecordId(request.recordId);
        if (request.rulesetName != null)
            reqBuilder.setRulesetName(request.rulesetName);
        if (request.skipComplianceChecks != null)
            reqBuilder.setSkipComplianceChecks(request.skipComplianceChecks);
        if (request.recordCall != null)
            reqBuilder.setRecordCall(request.recordCall);

        var resp = gate.dial(reqBuilder.build());
        var result = new DialResult();
        result.phoneNumber = resp.getPhoneNumber();
        result.callerId = resp.getCallerId();
        result.callSid = resp.getCallSid();
        result.callType = resp.getCallType().name().replace("CALL_TYPE_", "").toLowerCase();
        result.orgId = resp.getOrgId();
        result.partnerAgentId = resp.getPartnerAgentId();
        result.attempted = resp.getAttempted();
        result.status = resp.getStatus();
        result.callerSid = null; // not present in v3 DialResponse
        return result;
    }

    public RecordingStatus getRecordingStatus(String agentId) {
        var resp = gate.getRecordingStatus(
                build.buf.gen.tcnapi.exile.gate.v3.GetRecordingStatusRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new RecordingStatus(resp.getIsRecording());
    }

    public RecordingStatus startRecording(String agentId) {
        gate.startCallRecording(
                build.buf.gen.tcnapi.exile.gate.v3.StartCallRecordingRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new RecordingStatus(true);
    }

    public RecordingStatus stopRecording(String agentId) {
        gate.stopCallRecording(
                build.buf.gen.tcnapi.exile.gate.v3.StopCallRecordingRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new RecordingStatus(false);
    }

    public Object listPauseCodes(String agentId) {
        var resp = gate.listHuntGroupPauseCodes(
                build.buf.gen.tcnapi.exile.gate.v3.ListHuntGroupPauseCodesRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return resp.getPauseCodesList();
    }

    public SuccessResult simpleHold(String agentId) {
        gate.setHoldState(
                build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.newBuilder()
                        .setPartnerAgentId(agentId)
                        .setTarget(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldTarget.HOLD_TARGET_CALL)
                        .setAction(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldAction.HOLD_ACTION_HOLD)
                        .build());
        return new SuccessResult();
    }

    public SuccessResult simpleUnhold(String agentId) {
        gate.setHoldState(
                build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.newBuilder()
                        .setPartnerAgentId(agentId)
                        .setTarget(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldTarget.HOLD_TARGET_CALL)
                        .setAction(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldAction.HOLD_ACTION_UNHOLD)
                        .build());
        return new SuccessResult();
    }

    public SuccessResult mute(String agentId) {
        gate.muteAgent(
                build.buf.gen.tcnapi.exile.gate.v3.MuteAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new SuccessResult();
    }

    public SuccessResult unmute(String agentId) {
        gate.unmuteAgent(
                build.buf.gen.tcnapi.exile.gate.v3.UnmuteAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new SuccessResult();
    }

    public SuccessResult addCallResponse(String agentId, CallResponseRequest request) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v3.AddAgentCallResponseRequest.newBuilder()
                .setPartnerAgentId(agentId)
                .setCallSid(Long.parseLong(request.callSid))
                .setKey(request.key)
                .setValue(request.value);
        if (request.callType != null && !request.callType.isBlank()) {
            String ct = request.callType.toUpperCase();
            if (!ct.startsWith("CALL_TYPE_")) ct = "CALL_TYPE_" + ct;
            reqBuilder.setCallType(build.buf.gen.tcnapi.exile.gate.v3.CallType.valueOf(ct));
        }
        if (request.currentSessionId != null)
            reqBuilder.setCurrentSessionId(String.valueOf(request.currentSessionId));

        gate.addAgentCallResponse(reqBuilder.build());
        return new SuccessResult();
    }

    // ========== Helpers ==========

    protected AgentInfo toAgentInfo(build.buf.gen.tcnapi.exile.gate.v3.Agent a, boolean fetchRecordingStatus) {
        var info = new AgentInfo();
        info.userId = a.getUserId();
        info.orgId = a.getOrgId();
        info.partnerAgentId = a.getPartnerAgentId();
        info.username = a.getUsername();
        info.firstName = a.getFirstName();
        info.lastName = a.getLastName();
        String sid = a.getCurrentSessionId();
        info.currentSessionId = (sid != null && !sid.isEmpty()) ? Long.parseLong(sid) : null;
        info.agentState = a.getAgentState().name();
        info.isLoggedIn = a.getIsLoggedIn();
        info.isRecording = fetchRecordingStatus ? a.getIsRecording() : null;
        info.agentIsMuted = a.getIsMuted();
        if (a.hasConnectedParty()) {
            var cp = new AgentDto.ConnectedParty();
            cp.callSid = String.valueOf(a.getConnectedParty().getCallSid());
            cp.callType = a.getConnectedParty().getCallType().name().replace("CALL_TYPE_", "").toLowerCase();
            cp.inbound = a.getConnectedParty().getIsInbound();
            info.connectedParty = cp;
        }
        return info;
    }
}
