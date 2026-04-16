package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.model.CallType;
import com.tcn.exile.model.Filter;
import com.tcn.exile.web.dto.VoiceRecordingDownloadLinkDto;
import com.tcn.exile.web.dto.VoiceRecordingDto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for voice recording endpoints. No framework dependencies. */
public class VoiceRecordingsHandler {

  private static final Logger log = LoggerFactory.getLogger(VoiceRecordingsHandler.class);

  private final ExileClientManager clientManager;

  public VoiceRecordingsHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  /**
   * Search voice recordings with filter options.
   *
   * @param searchOption list of search options, each in format "field,OPERATOR,value" or three
   *     separate elements [field, operator, value]
   * @return list of matching recordings, or null if input validation fails (caller should return
   *     400 with the error message from {@link #validateSearchOptions})
   */
  public List<VoiceRecordingDto> searchVoiceRecordings(List<String> searchOption) {
    log.debug("searchVoiceRecordings with options: {}", searchOption);

    var filters = parseSearchOptions(searchOption);

    log.debug("SearchVoiceRecordings with {} filters", filters.size());
    var page = getClient().recordings().searchVoiceRecordings(filters, null, 0);
    return VoiceRecordingDto.fromV3List(page.items());
  }

  /**
   * Validates search options without executing the search. Returns null if valid, or an error
   * message if invalid. Useful for controllers that need to return framework-specific error
   * responses.
   */
  public String validateSearchOptions(List<String> searchOption) {
    if (searchOption == null || searchOption.isEmpty()) {
      return "At least one search option is required. Format: ?searchOption=field,OPERATOR,value";
    }
    try {
      parseSearchOptions(searchOption);
      return null;
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }

  public VoiceRecordingDownloadLinkDto getDownloadLink(
      String recordingId, String startOffset, String endOffset) {
    log.debug(
        "getVoiceRecordingDownloadLink({}, startOffset={}, endOffset={})",
        recordingId,
        startOffset,
        endOffset);

    if (recordingId == null || recordingId.isBlank()) {
      throw new IllegalArgumentException("recordingId is required");
    }

    Duration start = null;
    Duration end = null;

    if (startOffset != null && !startOffset.isEmpty()) {
      start = parseDuration(startOffset);
    }
    if (endOffset != null && !endOffset.isEmpty()) {
      end = parseDuration(endOffset);
    }

    var links = getClient().recordings().getDownloadLink(recordingId, start, end);
    return VoiceRecordingDownloadLinkDto.fromDownloadLinks(links);
  }

  public List<String> listSearchableFields() {
    log.debug("listSearchableRecordingFields()");
    return getClient().recordings().listSearchableFields();
  }

  public void createRecordingLabel(long callSid, String callTypeStr, String key, String value) {
    log.debug(
        "createRecordingLabel(callSid={}, callType={}, key={}, value={})",
        callSid,
        callTypeStr,
        key,
        value);

    if (callSid == 0) {
      throw new IllegalArgumentException("callSid is required and must be non-zero");
    }
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key is required");
    }

    CallType callType = parseCallType(callTypeStr);

    getClient().recordings().createLabel(callSid, callType, key, value);

    log.info("Successfully created recording label for callSid={}, key={}", callSid, key);
  }

  // ==================== Parsing helpers ====================

  private List<Filter> parseSearchOptions(List<String> searchOption) {
    if (searchOption == null || searchOption.isEmpty()) {
      throw new IllegalArgumentException(
          "At least one search option is required. Format: ?searchOption=field,OPERATOR,value");
    }

    var filters = new ArrayList<Filter>();

    // Handle the case where search options arrive as three separate query params
    if (searchOption.size() == 3
        && !searchOption.get(0).contains(",")
        && !searchOption.get(1).contains(",")
        && !searchOption.get(2).contains(",")) {
      String field = searchOption.get(0);
      String operatorStr = searchOption.get(1);
      String value = searchOption.get(2);

      Filter.Operator operator = parseOperator(operatorStr);
      filters.add(new Filter(field, operator, value));
      log.debug(
          "Added search option: field='{}', operator='{}', value='{}'", field, operatorStr, value);
    } else {
      for (String option : searchOption) {
        String[] parts = option.split(",", 3);
        if (parts.length != 3) {
          throw new IllegalArgumentException(
              String.format(
                  "Invalid search option format: '%s'. Expected format:"
                      + " 'field,OPERATOR,value' (e.g.,"
                      + " 'client_phone.region_name,CONTAINS,California')",
                  option));
        }
        var field = parts[0];
        var operatorStr = parts[1];
        var value = parts[2];

        Filter.Operator operator = parseOperator(operatorStr);
        filters.add(new Filter(field, operator, value));
        log.debug(
            "Added search option: field='{}', operator='{}', value='{}'",
            field,
            operatorStr,
            value);
      }
    }

    return filters;
  }

  private Filter.Operator parseOperator(String operatorStr) {
    try {
      return Filter.Operator.valueOf(operatorStr);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid operator '%s'. Valid operators are: EQUAL, CONTAINS, NOT_EQUAL",
              operatorStr));
    }
  }

  /**
   * Parse a duration string in the format "seconds.milliseconds" into a Java Duration.
   *
   * @param durationStr Duration string (e.g., "5.250" for 5.25 seconds)
   * @return Duration object
   * @throws IllegalArgumentException if the format is invalid
   */
  static Duration parseDuration(String durationStr) {
    try {
      String cleaned =
          durationStr.endsWith("s")
              ? durationStr.substring(0, durationStr.length() - 1)
              : durationStr;
      if (cleaned.contains(".")) {
        String[] parts = cleaned.split("\\.", 2);
        long seconds = Long.parseLong(parts[0]);
        long nanos = 0;
        if (parts.length > 1) {
          String fracStr = parts[1];
          while (fracStr.length() < 9) fracStr += "0";
          if (fracStr.length() > 9) fracStr = fracStr.substring(0, 9);
          nanos = Long.parseLong(fracStr);
        }
        return Duration.ofSeconds(seconds, nanos);
      } else {
        return Duration.ofSeconds(Long.parseLong(cleaned));
      }
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Invalid duration format: "
              + durationStr
              + ". Expected format: seconds.milliseconds (e.g., '5.250')",
          e);
    }
  }

  static CallType parseCallType(String callTypeStr) {
    try {
      String normalized = callTypeStr.toUpperCase();
      if (normalized.startsWith("CALL_TYPE_")) {
        normalized = normalized.substring("CALL_TYPE_".length());
      }
      return CallType.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid call type '%s'. Valid types: INBOUND, OUTBOUND, PREVIEW, MANUAL, MAC",
              callTypeStr));
    }
  }
}
