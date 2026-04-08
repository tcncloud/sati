package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import tcnapi.exile.agent.v3.*;

/** Thin wrapper around the v3 AgentService gRPC stub. */
public final class AgentService {

  private final AgentServiceGrpc.AgentServiceBlockingStub stub;

  public AgentService(ManagedChannel channel) {
    this.stub = AgentServiceGrpc.newBlockingStub(channel);
  }

  public GetAgentResponse getAgent(GetAgentRequest request) {
    return stub.getAgent(request);
  }

  public ListAgentsResponse listAgents(ListAgentsRequest request) {
    return stub.listAgents(request);
  }

  public UpsertAgentResponse upsertAgent(UpsertAgentRequest request) {
    return stub.upsertAgent(request);
  }

  public SetAgentCredentialsResponse setAgentCredentials(SetAgentCredentialsRequest request) {
    return stub.setAgentCredentials(request);
  }

  public GetAgentStatusResponse getAgentStatus(GetAgentStatusRequest request) {
    return stub.getAgentStatus(request);
  }

  public UpdateAgentStatusResponse updateAgentStatus(UpdateAgentStatusRequest request) {
    return stub.updateAgentStatus(request);
  }

  public MuteAgentResponse muteAgent(MuteAgentRequest request) {
    return stub.muteAgent(request);
  }

  public UnmuteAgentResponse unmuteAgent(UnmuteAgentRequest request) {
    return stub.unmuteAgent(request);
  }

  public AddAgentCallResponseResponse addAgentCallResponse(AddAgentCallResponseRequest request) {
    return stub.addAgentCallResponse(request);
  }

  public ListHuntGroupPauseCodesResponse listHuntGroupPauseCodes(
      ListHuntGroupPauseCodesRequest request) {
    return stub.listHuntGroupPauseCodes(request);
  }

  public ListSkillsResponse listSkills(ListSkillsRequest request) {
    return stub.listSkills(request);
  }

  public ListAgentSkillsResponse listAgentSkills(ListAgentSkillsRequest request) {
    return stub.listAgentSkills(request);
  }

  public AssignAgentSkillResponse assignAgentSkill(AssignAgentSkillRequest request) {
    return stub.assignAgentSkill(request);
  }

  public UnassignAgentSkillResponse unassignAgentSkill(UnassignAgentSkillRequest request) {
    return stub.unassignAgentSkill(request);
  }
}
