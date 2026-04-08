package com.tcn.exile.service;

import com.tcn.exile.model.CallType;
import io.grpc.ManagedChannel;
import java.util.Map;
import tcnapi.exile.call.v3.*;

/** Call control operations. No proto types in the public API. */
public final class CallService {

  private final CallServiceGrpc.CallServiceBlockingStub stub;

  CallService(ManagedChannel channel) {
    this.stub = CallServiceGrpc.newBlockingStub(channel);
  }

  public record DialResult(
      String phoneNumber,
      String callerId,
      long callSid,
      CallType callType,
      String orgId,
      String partnerAgentId,
      boolean attempted,
      String status) {}

  public DialResult dial(String partnerAgentId, String phoneNumber, String callerId,
      String poolId, String recordId, String rulesetName, Boolean skipCompliance,
      Boolean recordCall) {
    var req = DialRequest.newBuilder()
        .setPartnerAgentId(partnerAgentId)
        .setPhoneNumber(phoneNumber);
    if (callerId != null) req.setCallerId(callerId);
    if (poolId != null) req.setPoolId(poolId);
    if (recordId != null) req.setRecordId(recordId);
    if (rulesetName != null) req.setRulesetName(rulesetName);
    if (skipCompliance != null) req.setSkipComplianceChecks(skipCompliance);
    if (recordCall != null) req.setRecordCall(recordCall);
    var resp = stub.dial(req.build());
    return new DialResult(
        resp.getPhoneNumber(),
        resp.getCallerId(),
        resp.getCallSid(),
        com.tcn.exile.internal.ProtoConverter.toCallType(resp.getCallType()),
        resp.getOrgId(),
        resp.getPartnerAgentId(),
        resp.getAttempted(),
        resp.getStatus());
  }

  public void transfer(String partnerAgentId, String kind, String action,
      String destAgentId, String destPhone, Map<String, Long> destSkills) {
    var req = TransferRequest.newBuilder().setPartnerAgentId(partnerAgentId);
    req.setKind(TransferRequest.TransferKind.valueOf("TRANSFER_KIND_" + kind));
    req.setAction(TransferRequest.TransferAction.valueOf("TRANSFER_ACTION_" + action));
    if (destAgentId != null) {
      req.setAgent(TransferRequest.AgentDestination.newBuilder()
          .setPartnerAgentId(destAgentId));
    } else if (destPhone != null) {
      req.setOutbound(TransferRequest.OutboundDestination.newBuilder()
          .setPhoneNumber(destPhone));
    } else if (destSkills != null) {
      req.setQueue(TransferRequest.QueueDestination.newBuilder().putAllRequiredSkills(destSkills));
    }
    stub.transfer(req.build());
  }

  public enum HoldTarget { CALL, TRANSFER_CALLER, TRANSFER_AGENT }
  public enum HoldAction { HOLD, UNHOLD }

  public void setHoldState(String partnerAgentId, HoldTarget target, HoldAction action) {
    stub.setHoldState(
        SetHoldStateRequest.newBuilder()
            .setPartnerAgentId(partnerAgentId)
            .setTarget(SetHoldStateRequest.HoldTarget.valueOf(
                "HOLD_TARGET_" + target.name()))
            .setAction(SetHoldStateRequest.HoldAction.valueOf(
                "HOLD_ACTION_" + action.name()))
            .build());
  }

  public void startCallRecording(String partnerAgentId) {
    stub.startCallRecording(
        StartCallRecordingRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
  }

  public void stopCallRecording(String partnerAgentId) {
    stub.stopCallRecording(
        StopCallRecordingRequest.newBuilder().setPartnerAgentId(partnerAgentId).build());
  }

  public boolean getRecordingStatus(String partnerAgentId) {
    return stub
        .getRecordingStatus(
            GetRecordingStatusRequest.newBuilder().setPartnerAgentId(partnerAgentId).build())
        .getIsRecording();
  }

  public java.util.List<String> listComplianceRulesets() {
    return stub
        .listComplianceRulesets(ListComplianceRulesetsRequest.getDefaultInstance())
        .getRulesetNamesList();
  }
}
