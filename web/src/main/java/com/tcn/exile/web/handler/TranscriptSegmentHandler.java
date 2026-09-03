package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.web.dto.CallTranscriptSegmentDto;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for the call transcript segments endpoint. No framework dependencies. */
public class TranscriptSegmentHandler {

  private static final Logger log = LoggerFactory.getLogger(TranscriptSegmentHandler.class);

  private final ExileClientManager clientManager;

  public TranscriptSegmentHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  public List<CallTranscriptSegmentDto> getCallTranscriptSegments(
      long callSid,
      String callTypeStr,
      String label,
      String value,
      String startOffset,
      String endOffset) {
    log.debug(
        "getCallTranscriptSegments(callSid={}, callType={}, label={}, value={}, startOffset={}, endOffset={})",
        callSid,
        callTypeStr,
        label,
        value,
        startOffset,
        endOffset);

    if (callSid == 0) {
      throw new IllegalArgumentException("callSid is required and must be non-zero");
    }
    if (callTypeStr == null || callTypeStr.isBlank()) {
      throw new IllegalArgumentException("callType is required");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("label is required");
    }
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("value is required");
    }
    var callType = VoiceRecordingsHandler.parseCallType(callTypeStr);

    Duration start = null;
    Duration end = null;
    if (startOffset != null && !startOffset.isEmpty()) {
      start = VoiceRecordingsHandler.parseDuration(startOffset);
    }
    if (endOffset != null && !endOffset.isEmpty()) {
      end = VoiceRecordingsHandler.parseDuration(endOffset);
    }

    return getClient()
        .transcripts()
        .getCallTranscriptSegments(callSid, callType, label, value, start, end)
        .stream()
        .map(CallTranscriptSegmentDto::from)
        .toList();
  }
}
