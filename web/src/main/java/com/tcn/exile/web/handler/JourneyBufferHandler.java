package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.model.DataRecord;
import com.tcn.exile.web.dto.JourneyBufferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for journey buffer endpoints. No framework dependencies. */
public class JourneyBufferHandler {

  private static final Logger log = LoggerFactory.getLogger(JourneyBufferHandler.class);

  private final ExileClientManager clientManager;

  public JourneyBufferHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  public JourneyBufferResponse addRecordToJourneyBuffer(DataRecord dataRecord) {
    log.debug(
        "addRecordToJourneyBuffer with poolId={}, recordId={}",
        dataRecord.poolId(),
        dataRecord.recordId());

    if (dataRecord.poolId() == null || dataRecord.poolId().isBlank()) {
      throw new IllegalArgumentException("record.poolId is required");
    }

    var status = getClient().journey().addRecordToJourneyBuffer(dataRecord);
    return JourneyBufferResponse.fromJourneyStatus(status);
  }
}
