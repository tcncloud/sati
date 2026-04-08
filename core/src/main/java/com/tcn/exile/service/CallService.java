package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import tcnapi.exile.call.v3.*;

/** Thin wrapper around the v3 CallService gRPC stub. */
public final class CallService {

  private final CallServiceGrpc.CallServiceBlockingStub stub;

  public CallService(ManagedChannel channel) {
    this.stub = CallServiceGrpc.newBlockingStub(channel);
  }

  public DialResponse dial(DialRequest request) {
    return stub.dial(request);
  }

  public TransferResponse transfer(TransferRequest request) {
    return stub.transfer(request);
  }

  public SetHoldStateResponse setHoldState(SetHoldStateRequest request) {
    return stub.setHoldState(request);
  }

  public StartCallRecordingResponse startCallRecording(StartCallRecordingRequest request) {
    return stub.startCallRecording(request);
  }

  public StopCallRecordingResponse stopCallRecording(StopCallRecordingRequest request) {
    return stub.stopCallRecording(request);
  }

  public GetRecordingStatusResponse getRecordingStatus(GetRecordingStatusRequest request) {
    return stub.getRecordingStatus(request);
  }

  public ListComplianceRulesetsResponse listComplianceRulesets(
      ListComplianceRulesetsRequest request) {
    return stub.listComplianceRulesets(request);
  }
}
