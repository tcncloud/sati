package com.tcn.exile.models;

import java.util.Objects;

public record ManualDialResult(String phoneNumber, String callerId, long callSid, String callType, String orgId,
                               String partnerAgentId) {
  public ManualDialResult

  {
    Objects.nonNull(phoneNumber);
    Objects.nonNull(callerId);
    Objects.nonNull(callSid);

    Objects.nonNull(callType);
    Objects.nonNull(orgId);
    Objects.nonNull(partnerAgentId);
  }

  public static ManualDialResult fromProto(tcnapi.exile.gate.v1.Service.DialRes result) {
    if (result == null) {
      throw new IllegalArgumentException("result cannot be null");
    }
    String callType = "";

    switch (result.getCallType()) {
      case CALL_TYPE_INBOUND:
        callType = "inbound";
        break;
      case CALL_TYPE_OUTBOUND:
        callType = "outbound";
        break;
      case CALL_TYPE_MANUAL:
        callType = "manual";
        break;
      case CALL_TYPE_MAC:
        callType = "outbound";
        break;
      case CALL_TYPE_PREVIEW:
        callType = "outbound";
        break;
      default:
        throw new IllegalArgumentException("Invalid call type: " + result.getCallType());
    }
    return new ManualDialResult(result.getPhoneNumber(), result.getCallerId(), result.getCallSid(),
        callType, result.getOrgId(), result.getPartnerAgentId());
  }
}