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
import com.tcn.exile.models.LookupType;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v2.Entities.ExileAgentCall;
import tcnapi.exile.gate.v2.Entities.ExileAgentResponse;
import tcnapi.exile.gate.v2.Entities.ExileTelephonyResult;

import java.util.Map;

@Singleton
public interface PluginInterface {
    String getName();

    boolean isRunning();

    PluginStatus getPluginStatus();




    /**
     * List available pools of data for interogation
     * 
     * @param jobId
     * @throws UnconfiguredException
     */
    void listPools(String jobId) throws UnconfiguredException;

    /**
     * Get pool status
     * 
     * @param jobId
     * @param satiPoolId
     * @throws UnconfiguredException
     */
    void getPoolStatus(String jobId, String satiPoolId) throws UnconfiguredException;

    /**
     * Stream the records belonging to a pool
     * 
     * @param jobId
     * @param satiPoolId
     * @throws UnconfiguredException
     */
    void getPoolRecords(String jobId, String satiPoolId) throws UnconfiguredException;

    /**
     * Search for records using lookup value and type and optionally a parent id
     * 
     * @param jobId
     * @param lookupType
     * @param lookupValue
     * @param satiParentId
     * @throws UnconfiguredException
     */
    void searchRecords(String jobId, LookupType lookupType, String lookupValue, @Nullable String satiParentId)
            throws UnconfiguredException;

    /**
     * Read fields from a record
     * 
     * @param jobId
     * @param recordId
     * @param fields
     * @throws UnconfiguredException
     */
    void readFields(String jobId, String recordId, String[] fields) throws UnconfiguredException;

    /**
     * Write fields to a record
     * 
     * @param jobId
     * @param recordId
     * @param fields
     * @throws UnconfiguredException
     */
    void writeFields(String jobId, String recordId, Map<String, String> fields) throws UnconfiguredException;

    /**
     * Create a payment record
     * 
     * @param jobId
     * @param recordId
     * @param fields
     * @throws UnconfiguredException
     */
    void createPayment(String jobId, String recordId, Map<String, String> fields) throws UnconfiguredException;

    /**
     * Create a payment record
     * 
     * @param jobId
     * @param recordId
     * @param partnerUserId
     * @param callId
     * @param callType
     * @throws UnconfiguredException
     */
    void popAccount(String jobId, String recordId, String partnerUserId, String callId, String callType)
            throws UnconfiguredException;

    // TBD execute logic

    /**
     * Handle agent call
     * 
     * @param jobId
     * @param exileAgentCall
     */
    void handleAgentCall(ExileAgentCall exileAgentCall);

    /**
     * Handle telephony result
     * 
     * @param jobId
     * @param exileTelephonyResult
     */
    void handleTelephonyResult(ExileTelephonyResult exileTelephonyResult);

    /**
     * Handle agent response
     * 
     * @param jobId
     * @param exileAgentResponse
     */
    void handleAgentRespose(ExileAgentResponse exileAgentResponse);

}
