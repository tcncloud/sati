package com.tcn.exile.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ConnectedParty(
    @JsonProperty("call_sid") long callSid,
    @JsonProperty("call_type") CallType callType,
    @JsonProperty("inbound") boolean inbound
) {
}
