package com.tcn.exile.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record RecordingResponse(
    @JsonProperty("recording") boolean recording
) {
}
