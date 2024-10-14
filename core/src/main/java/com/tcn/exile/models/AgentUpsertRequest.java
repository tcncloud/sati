package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotEmpty;

@Serdeable
public record AgentUpsertRequest(
    @NotEmpty String userId,
    @Nullable String username,
    @Nullable String firstName,
    @Nullable String lastName,
    @Nullable String partnerAgentId,
    @Nullable String password
) {
}
