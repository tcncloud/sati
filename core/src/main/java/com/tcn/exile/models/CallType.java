package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum CallType {
  inbound(0),
  outbound(1),
  preview(2),
  manual(3),
  mac(4);

  private final int value;

  CallType(int i) {
    value = i;
  }

  public int getValue() {
    return value;
  }
}
