package com.tcn.exile.service;

import static com.tcn.exile.internal.ProtoConverter.*;

import com.tcn.exile.internal.ProtoConverter;
import com.tcn.exile.model.*;
import io.grpc.ManagedChannel;
import java.util.List;
import java.util.stream.Collectors;
import tcnapi.exile.agent.v3.*;

/** Agent management operations. No proto types in the public API. */
public final class AgentService {

  private final AgentServiceGrpc.AgentServiceBlockingStub stub;

  AgentService(ManagedChannel channel) {
    this.stub = AgentServiceGrpc.newBlockingStub(channel);
  }

  public Agent getAgentByPartnerId(String partnerAgentId) {
    var resp = stub.getAgent(
        GetAgentRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
    return toAgent(resp.getAgent());
  }

  public Agent getAgentByUserId(String userId) {
    var resp = stub.getAgent(GetAgentRequest.newBuilder().setUserId(userId).build());
    return toAgent(resp.getAgent());
  }

  public Page<Agent> listAgents(Boolean loggedIn, AgentState state,
      boolean includeRecordingStatus, String pageToken, int pageSize) {
    var req = ListAgentsRequest.newBuilder()
        .setIncludeRecordingStatus(includeRecordingStatus)
        .setPageSize(pageSize);
    if (loggedIn != null) req.setLoggedIn(loggedIn);
    if (state != null) req.setState(fromAgentState(state));
    if (pageToken != null) req.setPageToken(pageToken);
    var resp = stub.listAgents(req.build());
    return new Page<>(
        resp.getAgentsList().stream().map(ProtoConverter::toAgent).collect(Collectors.toList()),
        resp.getNextPageToken());
  }

  public Agent upsertAgent(String partnerAgentId, String username, String firstName,
      String lastName) {
    var resp = stub.upsertAgent(
        UpsertAgentRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setUsername(username)
            .setFirstName(firstName)
            .setLastName(lastName)
            .build());
    return toAgent(resp.getAgent());
  }

  public void setAgentCredentials(String partnerAgentId, String password) {
    stub.setAgentCredentials(
        SetAgentCredentialsRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setPassword(password)
            .build());
  }

  public void updateAgentStatus(String partnerAgentId, AgentState newState, String reason) {
    stub.updateAgentStatus(
        UpdateAgentStatusRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setNewState(fromAgentState(newState))
            .setReason(reason != null ? reason : "")
            .build());
  }

  public void muteAgent(String partnerAgentId) {
    stub.muteAgent(MuteAgentRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
  }

  public void unmuteAgent(String partnerAgentId) {
    stub.unmuteAgent(UnmuteAgentRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
  }

  public void addAgentCallResponse(String partnerAgentId, long callSid, CallType callType,
      String sessionId, String key, String value) {
    stub.addAgentCallResponse(
        AddAgentCallResponseRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setCallSid(callSid)
            .setCallType(
                tcnapi.exile.types.v3.CallType.valueOf("CALL_TYPE_" + callType.name()))
            .setCurrentSessionId(sessionId)
            .setKey(key)
            .setValue(value)
            .build());
  }

  public List<Skill> listSkills() {
    var resp = stub.listSkills(ListSkillsRequest.getDefaultInstance());
    return resp.getSkillsList().stream().map(ProtoConverter::toSkill).collect(Collectors.toList());
  }

  public List<Skill> listAgentSkills(String partnerAgentId) {
    var resp = stub.listAgentSkills(
        ListAgentSkillsRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
    return resp.getSkillsList().stream().map(ProtoConverter::toSkill).collect(Collectors.toList());
  }

  public void assignAgentSkill(String partnerAgentId, String skillId, long proficiency) {
    stub.assignAgentSkill(
        AssignAgentSkillRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setSkillId(skillId)
            .setProficiency(proficiency)
            .build());
  }

  public void unassignAgentSkill(String partnerAgentId, String skillId) {
    stub.unassignAgentSkill(
        UnassignAgentSkillRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setSkillId(skillId)
            .build());
  }
}
