package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import tcnapi.exile.config.v3.*;

/** Thin wrapper around the v3 ConfigService gRPC stub. */
public final class ConfigService {

  private final ConfigServiceGrpc.ConfigServiceBlockingStub stub;

  public ConfigService(ManagedChannel channel) {
    this.stub = ConfigServiceGrpc.newBlockingStub(channel);
  }

  public GetClientConfigurationResponse getClientConfiguration(
      GetClientConfigurationRequest request) {
    return stub.getClientConfiguration(request);
  }

  public GetOrganizationInfoResponse getOrganizationInfo(GetOrganizationInfoRequest request) {
    return stub.getOrganizationInfo(request);
  }

  public RotateCertificateResponse rotateCertificate(RotateCertificateRequest request) {
    return stub.rotateCertificate(request);
  }

  public LogResponse log(LogRequest request) {
    return stub.log(request);
  }

  public AddRecordToJourneyBufferResponse addRecordToJourneyBuffer(
      AddRecordToJourneyBufferRequest request) {
    return stub.addRecordToJourneyBuffer(request);
  }
}
