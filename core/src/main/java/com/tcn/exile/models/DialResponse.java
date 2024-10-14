package com.tcn.exile.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record DialResponse(
    @JsonProperty("phone_number") String phoneNumber,
    @JsonProperty("caller_id") String callerId,
    @JsonProperty("caller_sid") Long callSid,
    @JsonProperty("call_type") CallType callType,
    @JsonProperty("org_id") String orgId,
    @JsonProperty("partner_agent_id") String partnerAgentId) {
}
