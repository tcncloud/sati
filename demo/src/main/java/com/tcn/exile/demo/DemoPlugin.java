package com.tcn.exile.demo;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.LookupType;
import com.tcn.exile.plugin.PluginInterface;
import com.tcn.exile.plugin.PluginStatus;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v2.Entities;
import tcnapi.exile.gate.v2.Entities.ExileAgentCall;
import tcnapi.exile.gate.v2.Entities.ExileAgentResponse;
import tcnapi.exile.gate.v2.Entities.ExileTelephonyResult;

import com.tcn.exile.models.PluginConfigEvent;
import io.micronaut.context.event.ApplicationEventListener;

import java.util.HashMap;
import java.util.Map;

@Singleton
public class DemoPlugin implements ApplicationEventListener<PluginConfigEvent>, PluginInterface {
    private static final Logger log = LoggerFactory.getLogger(DemoPlugin.class);
    private boolean running = false;

    @Override
    public String getName() {
        return "DemoPlugin";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public PluginStatus getPluginStatus() {
        return new PluginStatus(
            getName(),
            running,
            100, // queueMaxSize
            0,   // queueCompletedJobs
            0,   // queueActiveCount
            new HashMap<>(), // internalConfig
            new HashMap<>()  // internalStatus
        );
    }




    @Override
    public void listPools(String jobId) throws UnconfiguredException {
        log.info("Listing pools for job {}", jobId);
    }

    @Override
    public void getPoolStatus(String jobId, String satiPoolId) throws UnconfiguredException {
        log.info("Getting pool status for job {} and pool {}", jobId, satiPoolId);
    }

    @Override
    public void getPoolRecords(String jobId, String satiPoolId) throws UnconfiguredException {
        log.info("Getting pool records for job {} and pool {}", jobId, satiPoolId);
    }

    @Override
    public void searchRecords(String jobId, LookupType lookupType, String lookupValue, @Nullable String satiParentId) throws UnconfiguredException {
        log.info("Searching records for job {} with type {} and value {}", jobId, lookupType, lookupValue);
    }

    @Override
    public void readFields(String jobId, String recordId, String[] fields) throws UnconfiguredException {
        log.info("Reading fields for job {} and record {}", jobId, recordId);
    }

    @Override
    public void writeFields(String jobId, String recordId, Map<String, String> fields) throws UnconfiguredException {
        log.info("Writing fields for job {} and record {}", jobId, recordId);
    }

    @Override
    public void createPayment(String jobId, String recordId, Map<String, String> fields) throws UnconfiguredException {
        log.info("Creating payment for job {} and record {}", jobId, recordId);
    }

    @Override
    public void popAccount(String jobId, String recordId, String partnerUserId, String callId, String callType) throws UnconfiguredException {
        log.info("Popping account for job {} and record {}", jobId, recordId);
    }

    @Override
    public void handleAgentCall(String jobId, ExileAgentCall exileAgentCall) {
        log.info("Handling agent call for job {}: {}", jobId, exileAgentCall);
    }

    @Override
    public void handleTelephonyResult(String jobId, ExileTelephonyResult exileTelephonyResult) {
        log.info("Handling telephony result for job {}: {}", jobId, exileTelephonyResult);
    }

    @Override
    public void handleAgentRespose(String jobId, ExileAgentResponse exileAgentResponse) {
        log.info("Handling agent response for job {}: {}", jobId, exileAgentResponse);
    }

    @Override
    public void onApplicationEvent(PluginConfigEvent event) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onApplicationEvent'");
    }
} 