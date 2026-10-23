package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tcn.exile.service.MessagingService;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SendMessageResponseDto(String messageRef) {

  public static SendMessageResponseDto from(MessagingService.SentMessage sent) {
    return new SendMessageResponseDto(sent.messageRef());
  }
}
