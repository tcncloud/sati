package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.service.ScrubListService;
import com.tcn.exile.web.dto.ScrubListDto;
import com.tcn.exile.web.dto.ScrubListType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for scrub list endpoints. No framework dependencies. */
public class ScrubListsHandler {

  private static final Logger log = LoggerFactory.getLogger(ScrubListsHandler.class);

  private final ExileClientManager clientManager;

  public ScrubListsHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  public List<ScrubListDto> listScrubLists() {
    var scrubLists = getClient().scrubLists().listScrubLists();
    List<ScrubListDto> ret = new ArrayList<>();
    for (var scrubList : scrubLists) {
      ScrubListType scrubType = mapContentType(scrubList.contentType());
      ret.add(new ScrubListDto(scrubList.scrubListId(), scrubList.readOnly(), scrubType));
    }
    return ret;
  }

  public void upsertScrubListEntry(
      String scrubListId, String content, Instant expiration, String notes, String countryCode) {
    log.debug(
        "upsertScrubList({}, content={}, expiration={}, notes={}, countryCode={})",
        scrubListId,
        content,
        expiration,
        notes,
        countryCode);

    if (scrubListId == null || scrubListId.isBlank()) {
      throw new IllegalArgumentException("scrubListId is required");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content is required in the scrub list entry");
    }

    var entry = new ScrubListService.ScrubListEntry(content, expiration, notes, countryCode);
    getClient().scrubLists().updateEntry(scrubListId, entry);
  }

  public void deleteScrubListEntry(String scrubListId, String content) {
    log.debug("deleteScrubListEntry({}, {})", scrubListId, content);

    if (scrubListId == null || scrubListId.isBlank()) {
      throw new IllegalArgumentException("scrubListId is required");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content (the entry to delete) is required");
    }

    getClient().scrubLists().removeEntries(scrubListId, List.of(content));
  }

  private ScrubListType mapContentType(String contentType) {
    if (contentType == null) return ScrubListType.other;
    return switch (contentType) {
      case "CONTENT_TYPE_PHONE" -> ScrubListType.phone;
      case "CONTENT_TYPE_EMAIL" -> ScrubListType.email;
      default -> ScrubListType.other;
    };
  }
}
