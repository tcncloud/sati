package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScrubListDto(
    String scrubListId, boolean readOnly, @JsonProperty("contentType") ScrubListType scrubType) {}
