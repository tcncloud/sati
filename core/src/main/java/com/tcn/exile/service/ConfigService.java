package com.tcn.exile.service;

import com.tcn.exile.internal.ProtoConverter;
import io.grpc.ManagedChannel;
import java.util.Map;

/** Configuration and lifecycle operations. No proto types in the public API. */
public final class ConfigService {

  private final build.buf.gen.tcnapi.exile.gate.v3.ConfigServiceGrpc.ConfigServiceBlockingStub stub;

  ConfigService(ManagedChannel channel) {
    this.stub = build.buf.gen.tcnapi.exile.gate.v3.ConfigServiceGrpc.newBlockingStub(channel);
  }

  public record ClientConfiguration(
      String orgId, String orgName, String configName, Map<String, Object> configPayload) {}

  public record OrgInfo(String orgId, String orgName) {}

  public ClientConfiguration getClientConfiguration() {
    var resp =
        stub.getClientConfiguration(
            build.buf.gen.tcnapi.exile.gate.v3.GetClientConfigurationRequest.getDefaultInstance());
    return new ClientConfiguration(
        resp.getOrgId(),
        resp.getOrgName(),
        resp.getConfigName(),
        ProtoConverter.structToMap(resp.getConfigPayload()));
  }

  public OrgInfo getOrganizationInfo() {
    var resp =
        stub.getOrganizationInfo(
            build.buf.gen.tcnapi.exile.gate.v3.GetOrganizationInfoRequest.getDefaultInstance());
    return new OrgInfo(resp.getOrgId(), resp.getOrgName());
  }

  public String rotateCertificate(String certificateHash) {
    var resp =
        stub.rotateCertificate(
            build.buf.gen.tcnapi.exile.gate.v3.RotateCertificateRequest.newBuilder()
                .setCertificateHash(certificateHash)
                .build());
    return resp.getEncodedCertificate();
  }

  public void log(String payload) {
    stub.log(
        build.buf.gen.tcnapi.exile.gate.v3.LogRequest.newBuilder().setPayload(payload).build());
  }
}
