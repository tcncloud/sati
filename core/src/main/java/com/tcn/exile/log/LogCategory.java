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
package com.tcn.exile.log;

/**
 * Defines the categories of log messages for structured logging. Each category represents a
 * specific area of the application.
 */
public enum LogCategory {
  STARTUP("startup"), // Application lifecycle
  CONFIG("config"), // Configuration changes
  DATABASE("database"), // Database operations
  GRPC("grpc"), // gRPC communications
  PLUGIN("plugin"), // Plugin lifecycle and jobs
  QUEUE("queue"), // Job queue operations
  API("api"), // REST API operations
  SECURITY("security"), // Authentication/authorization
  PERFORMANCE("performance"); // Performance metrics

  private final String value;

  LogCategory(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }
}
