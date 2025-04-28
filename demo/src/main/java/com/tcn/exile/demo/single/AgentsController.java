package com.tcn.exile.demo.single;


import com.google.protobuf.StringValue;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.gateclients.v2.GateClient;
import com.tcn.exile.models.*;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v2.Entities;
import tcnapi.exile.gate.v2.Public;

import java.util.List;
import java.util.Map;

@Controller("/api/agents")
@OpenAPIDefinition(tags = {@Tag(name = "agents")})
public class AgentsController {
  private final static Logger log = LoggerFactory.getLogger(AgentsController.class);
  @Inject
  ConfigChangeWatcher configChangeWatcher;


  @Get
  @Tag(name = "agents")
  public List<Agent> listAgents() throws UnconfiguredException {
    log.debug("listAgents");
    var ret = configChangeWatcher.getGateClient().listAgents(Public.ListAgentsRequest.newBuilder().build());
    List<Agent> agents = new java.util.ArrayList<Agent>();
    while (ret.hasNext()) {
      var agent = ret.next();
      agents.add(new Agent(
          agent.getAgent().getUserId(),
          agent.getAgent().getPartnerAgentId(),
          agent.getAgent().getUsername(),
          agent.getAgent().getFirstName(),
          agent.getAgent().getLastName()
      ));
    }

    return agents;
  }

  @Post
  @Consumes(MediaType.APPLICATION_JSON)
  @Tag(name = "agents")
  public Agent createAgent(@Body AgentUpsertRequest agent) throws UnconfiguredException {
    log.debug("createAgent");
    // find
    var req = Public.UpsertAgentRequest.
        newBuilder().
        setUsername(agent.username());

    if (agent.firstName() != null) {
      req.setFirstName(agent.firstName());
    }
    if (agent.lastName() != null) {
      req.setLastName(agent.lastName());
    }
    if (agent.partnerAgentId() != null) {
      req.setPartnerAgentId(agent.partnerAgentId());
    }
    if (agent.password() != null) {
      req.setPassword(agent.password());
    }
    var ret = configChangeWatcher.getGateClient().upsertAgent(req.build());
    if (ret != null) {
      return new Agent(
          ret.getAgent().getUserId(),
          ret.getAgent().getPartnerAgentId(),
          ret.getAgent().getUsername(),
          ret.getAgent().getFirstName(),
          ret.getAgent().getLastName());
    }
    throw new RuntimeException("Failed to create agent");
  }

  @Put("{partnerAgentId}/dial")
  @Tag(name = "agents")
  public DialResponse dial(@PathVariable String partnerAgentId, @Body DialRequest req) throws UnconfiguredException {
    log.debug("dial {}", req);

    var dialReq = Public.DialRequest.newBuilder()
        .setPartnerAgentId(partnerAgentId)
        .setPhoneNumber(req.phoneNumber());
    if (req.callerId() != null) {
      dialReq.setCallerId(StringValue.of(req.callerId()));
    }
    if (req.poolId() != null) {
      dialReq.setPoolId(StringValue.of(req.poolId()));
    }
    if (req.recordId() != null) {
      dialReq.setRecordId(StringValue.of(req.recordId()));
    }

    var res = configChangeWatcher.getGateClient().dial(dialReq.build());
    if (res != null) {
        return new DialResponse(
            res.getPhoneNumber(),
            res.getCallerId(),
            res.getCallSid(),
            CallType.valueOf(res.getCallType().name()),
            res.getOrgId(),
            res.getPartnerAgentId()
        );
    }
    throw new RuntimeException("Failed to dial");
  }

  @Get("{partnerAgentId}/recording")
  @Tag(name = "agents")
  public RecordingResponse getRecording(@PathVariable String partnerAgentId) throws UnconfiguredException {
    log.debug("getRecording");
    var res = configChangeWatcher.getGateClient().getRecordingStatus(Public.GetRecordingStatusRequest.newBuilder().
        setPartnerAgentId(partnerAgentId).build());
    return new RecordingResponse(res.getIsRecording());
  }

  @Put("{partnerAgentId}/recording/{status}")
  @Tag(name = "agents")
  public RecordingResponse setRecording(@PathVariable String partnerAgentId, @PathVariable String status) throws UnconfiguredException {
    log.debug("setRecording");
    boolean res = false;
    if (status.equalsIgnoreCase("on")
        || status.equalsIgnoreCase("resume")
        || status.equalsIgnoreCase("start")
        || status.equalsIgnoreCase("true")) {
      configChangeWatcher.getGateClient().startCallRecording(Public.StartCallRecordingRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
      return new RecordingResponse(true);
    } else if (status.equalsIgnoreCase("off")
        || status.equalsIgnoreCase("stop")
        || status.equalsIgnoreCase("pause")
        || status.equalsIgnoreCase("paused")
        || status.equalsIgnoreCase("false")) {
      configChangeWatcher.getGateClient().stopCallRecording(Public.StopCallRecordingRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
      return new RecordingResponse(false);
    }
    throw new RuntimeException("Invalid status");
  }


  @Get("{partnerAgentId}/state")
  @Tag(name = "agents")
  public AgentStatus getState(@PathVariable String partnerAgentId) throws UnconfiguredException {
    log.debug("getState");
    var res = configChangeWatcher.getGateClient().getAgentStatus(Public.GetAgentStatusRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
    if (res.hasConnectedParty()) {
      return new AgentStatus(
          res.getPartnerAgentId(),
          AgentState.values()[res.getAgentState().getNumber()],
          res.getCurrentSessionId(),
          new ConnectedParty(
              res.getConnectedParty().getCallSid(),
              CallType.values()[res.getConnectedParty().getCallType().getNumber()],
              res.getConnectedParty().getIsInbound()
          )
      );
    } else {
      return new AgentStatus(
          res.getPartnerAgentId(),
          AgentState.values()[res.getAgentState().getNumber()],
          res.getCurrentSessionId(),
          null
      );

    }
  }

  @Put("{partnerAgentId}/state/{state}")
  @Tag(name = "agents")
  public SetAgentStatusResponse setState(@PathVariable String partnerAgentId, @PathVariable SetAgentState state/*, @Body PauseCodeReason pauseCodeReason*/) throws UnconfiguredException {
    log.debug("setState");
    var request = Public.UpdateAgentStatusRequest.newBuilder()
      .setPartnerAgentId(partnerAgentId)
      .setNewState(Entities.AgentState.values()[state.getValue()]);
    // if (pauseCodeReason != null && pauseCodeReason.reason() != null) {
    //   request.setReason(pauseCodeReason.reason());
    //   // request.setPauseCodeReason(pauseCodeReason.reason());
    // }
    var res = configChangeWatcher.getGateClient().updateAgentStatus(request.build());
    return new SetAgentStatusResponse();
  }

  @Get("{partnerAgentId}/pausecodes")
  @Tag(name = "agents")
  public List<String> listPauseCodes(@PathVariable String partnerAgentId) {
    log.debug("listPauseCodes");
    var res = configChangeWatcher.getGateClient().listHuntGroupPauseCodes(Public.ListHuntGroupPauseCodesRequest.newBuilder()
        .setPartnerAgentId(partnerAgentId)
        .build());
    return res.getPauseCodesList().stream().toList();
  }


  @Get("{partnerAgentId}/simplehold")
  @Tag(name = "agents")
  public Map<String, Object> putCallOnSimpleHold(@PathVariable String partnerAgentId) {
    var res = configChangeWatcher.getGateClient().putCallOnSimpleHold(Public.PutCallOnSimpleHoldRequest.newBuilder()
        .setPartnerAgentId(partnerAgentId)
        .build());
    return Map.of("success", true);

  }

  @Get("{partnerAgentId}/simpleunhold")
  @Tag(name = "agents")
  public Map<String, Object> removeCallFromSimpleHold(@PathVariable String partnerAgentId) {
    var res = configChangeWatcher.getGateClient().takeCallOffSimpleHold(Public.TakeCallOffSimpleHoldRequest.newBuilder()
        .setPartnerAgentId(partnerAgentId)
        .build());
    return Map.of("success", true);
  }

  @Put("{partnerAgentId}/callresponse")
  @Tag(name = "agents")
  public Map<String, Object> addAgentCallResponse(@PathVariable String partnerAgentId, @Body Public.AddAgentCallResponseRequest req) {
    var res = configChangeWatcher.getGateClient().addAgentCallResponse(req.toBuilder().setPartnerAgentId(partnerAgentId).build());
    return Map.of("success", true);
  }
}