package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One audio channel of a call. id is the channel number, not a unique key -- a transferred call
 * carries one thread per leg on the same channel. speaker names the channel that carried the words;
 * nothing in the transcript identifies who spoke, and userId is the agent who handled the leg,
 * stamped on every channel.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TranscriptThreadDto(int id, String speaker, String userId, String text) {}
