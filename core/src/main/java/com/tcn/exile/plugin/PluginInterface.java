package com.tcn.exile.plugin;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.LookupType;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import tcnapi.exile.gate.v1.Entities;
import tcnapi.exile.gate.v1.Service;

import java.util.Map;

@Singleton
public interface PluginInterface {
    String getName();

    boolean isRunning();

    PluginStatus getPluginStatus();

    @Deprecated
    void scheduleInfo(String jobId, Service.Info info) throws UnconfiguredException;

    @Deprecated
    void scheduleExileAgentCall(String jobId, Entities.ExileAgentCall exileAgentCall);

    @Deprecated
    void scheduleExileTelephonyResult(String jobId, Entities.ExileTelephonyResult exileTelephonyResult);

    @Deprecated
    void scheduleExileAgentRespose(String jobId, Entities.ExileAgentResponse exileAgentResponse);

    @Deprecated
    void scheduleExileNamedJob(String jobId, Service.ExileNamedJobRequest exileNamedJobRequest)
            throws UnconfiguredException;

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
     * @param satiRecordId
     * @param fields
     * @throws UnconfiguredException
     */
    void readFields(String jobId, String satiRecordId, String[] fields) throws UnconfiguredException;

    /**
     * Write fields to a record
     * 
     * @param jobId
     * @param satiRecordId
     * @param fields
     * @throws UnconfiguredException
     */
    void writeFields(String jobId, String satiRecordId, Map<String, String> fields) throws UnconfiguredException;

    /**
     * Create a payment record
     * 
     * @param jobId
     * @param satiRecordId
     * @param fields
     * @throws UnconfiguredException
     */
    void createPayment(String jobId, String satiRecordId, Map<String, String> fields) throws UnconfiguredException;

    /**
     * Create a payment record
     * 
     * @param jobId
     * @param satiRecordId
     * @param partnerUserId
     * @param callId
     * @param callType
     * @throws UnconfiguredException
     */
    void popAccount(String jobId, String satiRecordId, String partnerUserId, String callId, String callType)
            throws UnconfiguredException;

    // TBD execute logic

    /**
     * Handle agent call
     * 
     * @param jobId
     * @param exileAgentCall
     */
    void handleAgentCall(String jobId, Entities.ExileAgentCall exileAgentCall);

    /**
     * Handle telephony result
     * 
     * @param jobId
     * @param exileTelephonyResult
     */
    void handleTelephonyResult(String jobId, Entities.ExileTelephonyResult exileTelephonyResult);

    /**
     * Handle agent response
     * 
     * @param jobId
     * @param exileAgentResponse
     */
    void handleAgentRespose(String jobId, Entities.ExileAgentResponse exileAgentResponse);

}
