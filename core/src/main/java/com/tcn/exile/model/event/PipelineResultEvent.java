package com.tcn.exile.model.event;

import java.util.Map;

public record PipelineResultEvent(
    String name,
    String entrypointId,
    String orgId,
    String eventId,
    Map<String, String> metadata,
    Map<String, Exchange> exchanges,
    long recordCount) {

  public record Exchange(
      Map<String, String> target,
      String status,
      long inputRecordCount,
      long outputRecordCount,
      String systemMessage) {}
}
