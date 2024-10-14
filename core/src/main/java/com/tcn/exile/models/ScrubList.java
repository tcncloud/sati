package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ScrubList (String scrubListId, boolean readOnly, ScrubListType contentType) {

}