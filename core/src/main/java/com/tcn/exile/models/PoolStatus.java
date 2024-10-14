package com.tcn.exile.models;

public enum PoolStatus {
  READY(0),
  NOT_READY(1),
  BUSY(2);

  private final int status;

  PoolStatus(int i) {
    this.status = i;
  }

  public int getStatus() {
    return status;
  }
}
