package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotEmpty;

@Serdeable
public record DialRequest(@NotEmpty String phoneNumber, @Nullable String callerId) {
}
