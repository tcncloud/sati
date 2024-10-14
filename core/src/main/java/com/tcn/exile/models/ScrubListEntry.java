package com.tcn.exile.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotEmpty;

import java.util.Date;

@Serdeable
@Introspected
public record ScrubListEntry(
    @NotEmpty @JsonProperty("content") String content,
    @JsonProperty("expiration_date") @Nullable Date expirationDate,
    @Nullable @JsonProperty("notes") String notes,
    @Nullable @JsonProperty("country_code") String countryCode
) {
}
