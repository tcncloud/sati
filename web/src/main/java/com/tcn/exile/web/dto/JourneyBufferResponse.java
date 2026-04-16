package com.tcn.exile.web.dto;

import com.tcn.exile.service.JourneyService;

public record JourneyBufferResponse(String status) {

  public static JourneyBufferResponse fromJourneyStatus(
      JourneyService.JourneyBufferStatus journeyStatus) {
    return new JourneyBufferResponse(journeyStatus.name());
  }
}
