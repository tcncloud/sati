package com.tcn.exile.web.dto;

import com.tcn.exile.service.MessagingService;

public record MessagingCampaignDto(String campaignSid, String name, String channel) {

  public static MessagingCampaignDto from(MessagingService.Campaign c) {
    return new MessagingCampaignDto(Long.toString(c.campaignSid()), c.name(), c.channel());
  }
}
