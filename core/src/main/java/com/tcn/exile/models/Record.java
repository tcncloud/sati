package com.tcn.exile.models;

import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

public record Record(
    @NotNull String satiRecordId,
    @NotNull String satiParentId,
    @Nullable String satiPoolId,
    @Nullable String jsonRecordPayload
) {
}
