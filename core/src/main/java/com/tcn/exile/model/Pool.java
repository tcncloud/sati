package com.tcn.exile.model;

public record Pool(String poolId, String description, PoolStatus status, long recordCount) {

  public enum PoolStatus {
    UNSPECIFIED,
    READY,
    NOT_READY,
    BUSY
  }
}
