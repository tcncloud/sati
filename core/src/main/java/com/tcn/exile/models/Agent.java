package com.tcn.exile.models;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotEmpty;

@Serdeable
public record Agent(
    @NotEmpty String userId, 
    @Nullable String partnerAgentId, 
    @NotEmpty String username, 
    @Nullable String firstName, 
    @Nullable String lastName) {
    
}
