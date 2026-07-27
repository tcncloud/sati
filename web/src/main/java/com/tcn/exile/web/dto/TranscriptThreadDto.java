package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One audio channel of a call. id is the channel number, not a unique key -- a transferred call
 * carries one thread per agent leg, all on channel 2. userId is the agent who handled the leg and
 * is stamped on the customer channel too, so use speaker, never userId, to attribute speech.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TranscriptThreadDto(int id, String speaker, String userId, String text) {}
