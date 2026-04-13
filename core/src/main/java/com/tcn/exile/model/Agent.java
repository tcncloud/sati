package com.tcn.exile.model;

import java.util.Optional;

public record Agent(
    String userId,
    String orgId,
    String firstName,
    String lastName,
    String username,
    String partnerAgentId,
    String currentSessionId,
    AgentState state,
    boolean loggedIn,
    boolean muted,
    boolean recording,
    Optional<ConnectedParty> connectedParty) {

  public record ConnectedParty(long callSid, CallType callType, boolean inbound) {}
}
