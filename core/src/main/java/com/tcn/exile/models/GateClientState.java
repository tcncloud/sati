package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum GateClientState {
  RUNNING,
  STOPPED;
}
