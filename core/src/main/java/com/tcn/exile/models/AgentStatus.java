package com.tcn.exile.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.annotation.Nullable;

@Serdeable
public record AgentStatus(
  @JsonProperty("user_id") String userId,
  @JsonProperty("state") AgentState agentState,
  @JsonProperty("current_session_id") @Nullable  Long currentSessionId,
  @JsonProperty("connected_party") @Nullable ConnectedParty connectedParty
){}
