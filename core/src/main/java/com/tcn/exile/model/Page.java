package com.tcn.exile.model;

import java.util.List;

/** A page of results with an optional continuation token. */
public record Page<T>(List<T> items, String nextPageToken) {

  public boolean hasMore() {
    return nextPageToken != null && !nextPageToken.isEmpty();
  }
}
