package com.tcn.exile.service;

import com.tcn.exile.internal.ProtoConverter;
import com.tcn.exile.model.Record;
import io.grpc.ManagedChannel;
import java.util.Map;
import tcnapi.exile.config.v3.*;

/** Configuration and lifecycle operations. No proto types in the public API. */
public final class ConfigService {

  private final ConfigServiceGrpc.ConfigServiceBlockingStub stub;

  ConfigService(ManagedChannel channel) {
    this.stub = ConfigServiceGrpc.newBlockingStub(channel);
  }

  public record ClientConfiguration(
      String orgId, String orgName, String configName, Map<String, Object> configPayload) {}

  public record OrgInfo(String orgId, String orgName) {}

  public ClientConfiguration getClientConfiguration() {
    var resp = stub.getClientConfiguration(GetClientConfigurationRequest.getDefaultInstance());
    return new ClientConfiguration(
        resp.getOrgId(),
        resp.getOrgName(),
        resp.getConfigName(),
        ProtoConverter.structToMap(resp.getConfigPayload()));
  }

  public OrgInfo getOrganizationInfo() {
    var resp = stub.getOrganizationInfo(GetOrganizationInfoRequest.getDefaultInstance());
    return new OrgInfo(resp.getOrgId(), resp.getOrgName());
  }

  public String rotateCertificate(String certificateHash) {
    var resp = stub.rotateCertificate(
        RotateCertificateRequest.newBuilder().setCertificateHash(certificateHash).build());
    return resp.getEncodedCertificate();
  }

  public void log(String payload) {
    stub.log(LogRequest.newBuilder().setPayload(payload).build());
  }

  public enum JourneyBufferStatus { INSERTED, UPDATED, IGNORED, REJECTED, UNSPECIFIED }

  public JourneyBufferStatus addRecordToJourneyBuffer(Record record) {
    var resp = stub.addRecordToJourneyBuffer(
        AddRecordToJourneyBufferRequest.newBuilder()
            .setRecord(ProtoConverter.fromRecord(record))
            .build());
    return switch (resp.getStatus()) {
      case JOURNEY_BUFFER_STATUS_INSERTED -> JourneyBufferStatus.INSERTED;
      case JOURNEY_BUFFER_STATUS_UPDATED -> JourneyBufferStatus.UPDATED;
      case JOURNEY_BUFFER_STATUS_IGNORED -> JourneyBufferStatus.IGNORED;
      case JOURNEY_BUFFER_STATUS_REJECTED -> JourneyBufferStatus.REJECTED;
      default -> JourneyBufferStatus.UNSPECIFIED;
    };
  }
}
