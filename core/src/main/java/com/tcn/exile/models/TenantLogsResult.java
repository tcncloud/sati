/*
 *  (C) 2017-2025 TCN Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tcn.exile.models;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Serdeable
public class TenantLogsResult {
  private List<LogGroup> logGroups;
  private String nextPageToken;

  public List<LogGroup> getLogGroups() {
    return logGroups;
  }

  public void setLogGroups(List<LogGroup> logGroups) {
    this.logGroups = logGroups;
  }

  public String getNextPageToken() {
    return nextPageToken;
  }

  public void setNextPageToken(String nextPageToken) {
    this.nextPageToken = nextPageToken;
  }

  @Serdeable
  public static class LogGroup {
    private String name;
    private List<String> logs;
    private TimeRange timeRange;
    private Map<String, LogLevel> logLevels;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public List<String> getLogs() {
      return logs;
    }

    public void setLogs(List<String> logs) {
      this.logs = logs;
    }

    public TimeRange getTimeRange() {
      return timeRange;
    }

    public void setTimeRange(TimeRange timeRange) {
      this.timeRange = timeRange;
    }

    public Map<String, LogLevel> getLogLevels() {
      return logLevels;
    }

    public void setLogLevels(Map<String, LogLevel> logLevels) {
      this.logLevels = logLevels;
    }

    @Serdeable
    public static class TimeRange {
      private Instant startTime;
      private Instant endTime;

      public Instant getStartTime() {
        return startTime;
      }

      public void setStartTime(Instant startTime) {
        this.startTime = startTime;
      }

      public Instant getEndTime() {
        return endTime;
      }

      public void setEndTime(Instant endTime) {
        this.endTime = endTime;
      }
    }

    @Serdeable
    public enum LogLevel {
      DEBUG,
      INFO,
      WARNING,
      ERROR,
      FATAL
    }
  }

  // Convert from protobuf ListTenantLogsResult to our Serdeable model
  public static TenantLogsResult fromProto(
      build.buf.gen.tcnapi.exile.gate.v2.SubmitJobResultsRequest.ListTenantLogsResult proto) {
    TenantLogsResult result = new TenantLogsResult();
    result.setNextPageToken(proto.getNextPageToken());

    java.util.List<LogGroup> logGroups = new java.util.ArrayList<>();
    for (var protoLogGroup : proto.getLogGroupsList()) {
      LogGroup logGroup = new LogGroup();
      logGroup.setName(protoLogGroup.getName());
      logGroup.setLogs(protoLogGroup.getLogsList());

      if (protoLogGroup.hasTimeRange()) {
        LogGroup.TimeRange timeRange = new LogGroup.TimeRange();
        timeRange.setStartTime(
            Instant.ofEpochSecond(
                protoLogGroup.getTimeRange().getStartTime().getSeconds(),
                protoLogGroup.getTimeRange().getStartTime().getNanos()));
        timeRange.setEndTime(
            Instant.ofEpochSecond(
                protoLogGroup.getTimeRange().getEndTime().getSeconds(),
                protoLogGroup.getTimeRange().getEndTime().getNanos()));
        logGroup.setTimeRange(timeRange);
      }

      // Convert log levels map
      Map<String, LogGroup.LogLevel> logLevelsMap = new java.util.HashMap<>();
      for (var entry : protoLogGroup.getLogLevelsMap().entrySet()) {
        LogGroup.LogLevel level;
        switch (entry.getValue()) {
          case DEBUG:
            level = LogGroup.LogLevel.DEBUG;
            break;
          case INFO:
            level = LogGroup.LogLevel.INFO;
            break;
          case WARNING:
            level = LogGroup.LogLevel.WARNING;
            break;
          case ERROR:
            level = LogGroup.LogLevel.ERROR;
            break;
          case FATAL:
            level = LogGroup.LogLevel.FATAL;
            break;
          default:
            level = LogGroup.LogLevel.INFO;
        }
        logLevelsMap.put(entry.getKey(), level);
      }
      logGroup.setLogLevels(logLevelsMap);

      logGroups.add(logGroup);
    }
    result.setLogGroups(logGroups);

    return result;
  }
}
