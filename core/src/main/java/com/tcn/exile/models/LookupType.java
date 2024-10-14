package com.tcn.exile.models;

public enum LookupType {
  RECORD(0),
  PHONE(1),
  CLIENT_REF(1),
  ACCOUNT_NUMBER(2);

  private final int type;

  LookupType(int i) {
    this.type = i;
  }

  public int getType() {
    return type;
  }
}
