package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record GateClientStatus(GateClientState status) {
}

