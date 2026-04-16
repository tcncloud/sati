package com.tcn.exile.web.dto;

import com.tcn.exile.service.RecordingService;

public record VoiceRecordingDownloadLinkDto(String downloadLink, String playbackLink) {

  public static VoiceRecordingDownloadLinkDto fromDownloadLinks(
      RecordingService.DownloadLinks links) {
    return new VoiceRecordingDownloadLinkDto(links.downloadLink(), links.playbackLink());
  }
}
