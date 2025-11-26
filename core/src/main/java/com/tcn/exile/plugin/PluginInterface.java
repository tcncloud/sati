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
package com.tcn.exile.plugin;

import build.buf.gen.tcnapi.exile.gate.v2.*;
import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.PluginConfigEvent;

public interface PluginInterface {
  String getName();

  boolean isRunning();

  PluginStatus getPluginStatus();

  /**
   * List available pools of data for interogation
   *
   * @param jobId
   * @param listPools
   * @throws UnconfiguredException
   */
  void listPools(String jobId, StreamJobsResponse.ListPoolsRequest listPools)
      throws UnconfiguredException;

  /**
   * Get pool status
   *
   * @param jobId
   * @param satiPoolId
   * @throws UnconfiguredException
   */
  void getPoolStatus(String jobId, StreamJobsResponse.GetPoolStatusRequest satiPoolId)
      throws UnconfiguredException;

  /**
   * Stream the records belonging to a pool
   *
   * @param jobId
   * @param satiPoolId
   * @throws UnconfiguredException
   */
  void getPoolRecords(String jobId, StreamJobsResponse.GetPoolRecordsRequest satiPoolId)
      throws UnconfiguredException;

  /**
   * Handle agent call
   *
   * @param exileAgentCall
   */
  void handleAgentCall(ExileAgentCall exileAgentCall);

  /**
   * Handle telephony result
   *
   * @param exileTelephonyResult
   */
  void handleTelephonyResult(ExileTelephonyResult exileTelephonyResult);

  /** handle task */
  void handleTask(ExileTask exileTask);

  /**
   * Handle agent response
   *
   * @param exileAgentResponse
   */
  void handleAgentResponse(ExileAgentResponse exileAgentResponse);

  void searchRecords(String jobId, StreamJobsResponse.SearchRecordsRequest searchRecords);

  void readFields(String jobId, StreamJobsResponse.GetRecordFieldsRequest getRecordFields);

  void writeFields(String jobId, StreamJobsResponse.SetRecordFieldsRequest setRecordFields);

  void createPayment(String jobId, StreamJobsResponse.CreatePaymentRequest createPayment);

  void popAccount(String jobId, StreamJobsResponse.PopAccountRequest popAccount);

  void info(String jobId, StreamJobsResponse.InfoRequest info);

  SubmitJobResultsRequest.InfoResult info();

  void shutdown(String jobId, StreamJobsResponse.SeppukuRequest shutdown);

  void logger(String jobId, StreamJobsResponse.LoggingRequest log);

  void executeLogic(String jobId, StreamJobsResponse.ExecuteLogicRequest executeLogic);

  /**
   * Run system diagnostics and collect detailed information about the system environment, JVM
   * settings, memory usage, container details, and database connections. The resulting diagnostics
   * data is submitted back to the gate service.
   *
   * @param jobId The ID of the job
   * @param diagnosticsRequest The diagnostics request details
   */
  void runDiagnostics(String jobId, StreamJobsResponse.DiagnosticsRequest diagnosticsRequest);

  /**
   * List tenant logs by retrieving logs from memory and formatting them into log groups. The logs
   * are retrieved from the MemoryAppender and submitted back to the gate service.
   *
   * @param jobId The ID of the job
   * @param listTenantLogsRequest The list tenant logs request details
   */
  void listTenantLogs(String jobId, StreamJobsResponse.ListTenantLogsRequest listTenantLogsRequest);

  /**
   * Set the log level for a specific logger dynamically. This allows changing log levels at runtime
   * without restarting the application.
   *
   * @param jobId The ID of the job
   * @param setLogLevelRequest The set log level request details
   */
  void setLogLevel(String jobId, StreamJobsResponse.SetLogLevelRequest setLogLevelRequest);

  void setConfig(PluginConfigEvent config);

  void handleTransferInstance(ExileTransferInstance exileTransferInstance);

  void handleCallRecording(ExileCallRecording exileCallRecording);
}
