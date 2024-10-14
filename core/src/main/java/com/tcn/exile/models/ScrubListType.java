package com.tcn.exile.models;

public enum ScrubListType {
  phone_number(0),
  email(1),
  sms(2),
  other(3);

  private int value;

  public int getValue() {
    return value;
  }

  ScrubListType(int value) {
    this.value = value;
  }

  public static ScrubListType create(int value) {
    if (value >= ScrubListType.values().length) return other;
    return ScrubListType.values()[value];
  }
}
