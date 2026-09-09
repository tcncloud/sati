package com.tcn.exile.web.dto;

import java.util.Map;

public record SendSmsRequestDto(
    long campaignSid,
    String recipient,
    String body,
    String poolId,
    String recordId,
    Map<String, String> metadata) {}
