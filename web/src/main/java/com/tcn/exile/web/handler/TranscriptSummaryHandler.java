package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.web.dto.CallTranscriptSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for the call transcript + summary endpoint. No framework dependencies. */
public class TranscriptSummaryHandler {

  private static final Logger log = LoggerFactory.getLogger(TranscriptSummaryHandler.class);

  private final ExileClientManager clientManager;

  public TranscriptSummaryHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  public CallTranscriptSummaryDto getCallTranscriptSummary(long callSid, String callTypeStr) {
    log.debug("getCallTranscriptSummary(callSid={}, callType={})", callSid, callTypeStr);

    if (callSid == 0) {
      throw new IllegalArgumentException("callSid is required and must be non-zero");
    }
    if (callTypeStr == null || callTypeStr.isBlank()) {
      throw new IllegalArgumentException("callType is required");
    }
    var callType = VoiceRecordingsHandler.parseCallType(callTypeStr);

    return CallTranscriptSummaryDto.from(
        getClient().transcripts().getCallTranscriptSummary(callSid, callType));
  }
}
