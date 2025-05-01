/*
 *  (C) 2017-2025 TCN Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tcn.exile.demo.single;

import build.buf.gen.tcnapi.exile.gate.v2.*;
import com.google.protobuf.StringValue;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.*;
import com.tcn.exile.models.Agent;
import com.tcn.exile.models.AgentState;
import com.tcn.exile.models.CallType;
import com.tcn.exile.models.ConnectedParty;
import com.tcn.exile.models.DialRequest;
import com.tcn.exile.models.DialResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/api/agents")
@OpenAPIDefinition(tags = {@Tag(name = "agents")})
public class AgentsController {
  private static final Logger log = LoggerFactory.getLogger(AgentsController.class);
  @Inject ConfigChangeWatcher configChangeWatcher;

  @Get
  @Tag(name = "agents")
  public List<Agent> listAgents() throws UnconfiguredException {
    log.debug("listAgents");
    var ret =
        configChangeWatcher.getGateClient().listAgents(ListAgentsRequest.newBuilder().build());
    List<Agent> agents = new java.util.ArrayList<Agent>();
    while (ret.hasNext()) {
      var agent = ret.next();
      agents.add(
          new Agent(
              agent.getAgent().getUserId(),
              agent.getAgent().getPartnerAgentId(),
              agent.getAgent().getUsername(),
              agent.getAgent().getFirstName(),
              agent.getAgent().getLastName()));
    }

    return agents;
  }

  @Post
  @Consumes(MediaType.APPLICATION_JSON)
  @Tag(name = "agents")
  public Agent createAgent(@Body AgentUpsertRequest agent) throws UnconfiguredException {
    log.debug("createAgent");
    // find
    var req = UpsertAgentRequest.newBuilder().setUsername(agent.username());

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
  public DialResponse dial(@PathVariable String partnerAgentId, @Body DialRequest req)
      throws UnconfiguredException {
    log.debug("dial {}", req);

    var dialReq =
        build.buf.gen.tcnapi.exile.gate.v2.DialRequest.newBuilder()
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
          res.getPartnerAgentId());
    }
    throw new RuntimeException("Failed to dial");
  }

  @Get("{partnerAgentId}/recording")
  @Tag(name = "agents")
  public RecordingResponse getRecording(@PathVariable String partnerAgentId)
      throws UnconfiguredException {
    log.debug("getRecording");
    var res =
        configChangeWatcher
            .getGateClient()
            .getRecordingStatus(
                GetRecordingStatusRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
    return new RecordingResponse(res.getIsRecording());
  }

  @Put("{partnerAgentId}/recording/{status}")
  @Tag(name = "agents")
  public RecordingResponse setRecording(
      @PathVariable String partnerAgentId, @PathVariable String status)
      throws UnconfiguredException {
    log.debug("setRecording");
    boolean res = false;
    if (status.equalsIgnoreCase("on")
        || status.equalsIgnoreCase("resume")
        || status.equalsIgnoreCase("start")
        || status.equalsIgnoreCase("true")) {
      configChangeWatcher
          .getGateClient()
          .startCallRecording(
              StartCallRecordingRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
      return new RecordingResponse(true);
    } else if (status.equalsIgnoreCase("off")
        || status.equalsIgnoreCase("stop")
        || status.equalsIgnoreCase("pause")
        || status.equalsIgnoreCase("paused")
        || status.equalsIgnoreCase("false")) {
      configChangeWatcher
          .getGateClient()
          .stopCallRecording(
              StopCallRecordingRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
      return new RecordingResponse(false);
    }
    throw new RuntimeException("Invalid status");
  }

  @Get("{partnerAgentId}/state")
  @Tag(name = "agents")
  public AgentStatus getState(@PathVariable String partnerAgentId) throws UnconfiguredException {
    log.debug("getState");
    var res =
        configChangeWatcher
            .getGateClient()
            .getAgentStatus(
                GetAgentStatusRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
    if (res.hasConnectedParty()) {
      return new AgentStatus(
          res.getPartnerAgentId(),
          AgentState.values()[res.getAgentState().getNumber()],
          res.getCurrentSessionId(),
          new ConnectedParty(
              res.getConnectedParty().getCallSid(),
              CallType.values()[res.getConnectedParty().getCallType().getNumber()],
              res.getConnectedParty().getIsInbound()));
    } else {
      return new AgentStatus(
          res.getPartnerAgentId(),
          AgentState.values()[res.getAgentState().getNumber()],
          res.getCurrentSessionId(),
          null);
    }
  }

  @Put("{partnerAgentId}/state/{state}")
  @Tag(name = "agents")
  public SetAgentStatusResponse setState(
      @PathVariable String partnerAgentId,
      @PathVariable SetAgentState state /*, @Body PauseCodeReason pauseCodeReason*/)
      throws UnconfiguredException {
    log.debug("setState");
    var request =
        UpdateAgentStatusRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setNewState(build.buf.gen.tcnapi.exile.gate.v2.AgentState.values()[state.getValue()]);
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
    var res =
        configChangeWatcher
            .getGateClient()
            .listHuntGroupPauseCodes(
                ListHuntGroupPauseCodesRequest.newBuilder()
                    .setPartnerAgentId(partnerAgentId)
                    .build());
    return res.getPauseCodesList().stream().toList();
  }

  @Get("{partnerAgentId}/simplehold")
  @Tag(name = "agents")
  public Map<String, Object> putCallOnSimpleHold(@PathVariable String partnerAgentId) {
    var res =
        configChangeWatcher
            .getGateClient()
            .putCallOnSimpleHold(
                PutCallOnSimpleHoldRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
    return Map.of("success", true);
  }

  @Get("{partnerAgentId}/simpleunhold")
  @Tag(name = "agents")
  public Map<String, Object> removeCallFromSimpleHold(@PathVariable String partnerAgentId) {
    var res =
        configChangeWatcher
            .getGateClient()
            .takeCallOffSimpleHold(
                TakeCallOffSimpleHoldRequest.newBuilder()
                    .setPartnerAgentId(partnerAgentId)
                    .build());
    return Map.of("success", true);
  }

  @Put("{partnerAgentId}/callresponse")
  @Tag(name = "agents")
  public Map<String, Object> addAgentCallResponse(
      @PathVariable String partnerAgentId, @Body AddAgentCallResponseRequest req) {
    var res =
        configChangeWatcher
            .getGateClient()
            .addAgentCallResponse(req.toBuilder().setPartnerAgentId(partnerAgentId).build());
    return Map.of("success", true);
  }
}
