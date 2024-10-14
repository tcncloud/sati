package com.tcn.exile.models;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Pool(
    String satiPoolId,
    String displayName,
    PoolStatus status,
    @Nullable Integer size
) {
}
