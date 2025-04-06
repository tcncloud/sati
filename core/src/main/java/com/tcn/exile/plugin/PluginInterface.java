/* 
 *  Copyright 2017-2024 original authors
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
 */

package com.tcn.exile.plugin;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.PluginConfigEvent;
import tcnapi.exile.gate.v2.Entities.ExileAgentCall;
import tcnapi.exile.gate.v2.Entities.ExileAgentResponse;
import tcnapi.exile.gate.v2.Entities.ExileTelephonyResult;
import tcnapi.exile.gate.v2.Public;

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
    void listPools(String jobId, Public.StreamJobsResponse.ListPoolsRequest listPools) throws UnconfiguredException;

    /**
     * Get pool status
     * 
     * @param jobId
     * @param satiPoolId
     * @throws UnconfiguredException
     */
    void getPoolStatus(String jobId, Public.StreamJobsResponse.GetPoolStatusRequest satiPoolId) throws UnconfiguredException;

    /**
     * Stream the records belonging to a pool
     * 
     * @param jobId
     * @param satiPoolId
     * @throws UnconfiguredException
     */
    void getPoolRecords(String jobId, Public.StreamJobsResponse.GetPoolRecordsRequest satiPoolId) throws UnconfiguredException;





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

    /**
     * Handle agent response
     * 
     * @param exileAgentResponse
     */
    void handleAgentResponse(ExileAgentResponse exileAgentResponse);

    void searchRecords(String jobId, Public.StreamJobsResponse.SearchRecordsRequest searchRecords);

    void readFields(String jobId, Public.StreamJobsResponse.GetRecordFieldsRequest getRecordFields);

    void writeFields(String jobId, Public.StreamJobsResponse.SetRecordFieldsRequest setRecordFields);

    void createPayment(String jobId, Public.StreamJobsResponse.CreatePaymentRequest createPayment);

    void popAccount(String jobId, Public.StreamJobsResponse.PopAccountRequest popAccount);

    void info(String jobId, Public.StreamJobsResponse.InfoRequest info);
    Public.SubmitJobResultsRequest.InfoResult info();

    void shutdown(String jobId, Public.StreamJobsResponse.SeppukuRequest shutdown);

    void logger(String jobId, Public.StreamJobsResponse.LoggingRequest log);

    void executeLogic(String jobId, Public.StreamJobsResponse.ExecuteLogicRequest executeLogic);

    void setConfig(PluginConfigEvent config);
}
