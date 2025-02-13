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

package com.tcn.exile.gateclients;

import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.tcn.exile.config.ConfigEvent;
import com.tcn.exile.models.*;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Null;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.gate.v1.ExileGateServiceGrpc;
import tcnapi.exile.gate.v1.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Abstract base class for Gate Client implementations.
 * This class provides common functionality for interacting with the TCN Gate service.
 */
public abstract class GateClientAbstract implements ApplicationEventListener<ConfigEvent> {
  private static final Logger log = LoggerFactory.getLogger(GateClientAbstract.class);
  protected ConfigEvent event;
  protected ManagedChannel channel;

  /**
   * Initializes the Gate Client.
   * This method should be implemented by subclasses to perform any necessary setup.
   */
  public abstract void start();

  public GateClientAbstract() {}
  
  public GateClientAbstract(ConfigEvent configEvent) {
    this.event = configEvent;
    start();
  }


  public GateClientResponseStream getResponseStream() throws UnconfiguredException {
    return new GateClientResponseStream(getChannel());
  }

  /**
   * Handles configuration changes.
   * @param event The new configuration event.
   */
  @Override
  public void onApplicationEvent(ConfigEvent event) {
    log.debug("onApplicationEvent, config changed {}", event);
    this.event = event;
    if ((channel != null) && !channel.isShutdown() && !channel.isTerminated()) {
      this.shutdown();
    }
    start();
  }

  /**
   * Retrieves the current configuration.
   * @return The current ConfigEvent.
   * @throws UnconfiguredException if the client is not configured.
   */
  protected ConfigEvent getConfig() throws UnconfiguredException {
    if ((event == null) || event.isUnconfigured()) {
      throw new UnconfiguredException("TCN Gate client is unconfigured");
    }
    return event;
  }

  public void shutdown() {
    if (channel != null) {
      channel.shutdown();
      try {
        channel.awaitTermination(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        log.error("Channel shutdown interrupted", e);
        channel.shutdownNow();
        try {
          channel.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
          log.error("2nd Attempt to channel shutdown interrupted", ex);
        }
      }
      channel = null;
    }
  }

  /**
   * Gets or creates a gRPC channel for communication with the Gate service.
   * @return A ManagedChannel instance.
   * @throws UnconfiguredException if the client is not configured.
   */
  public ManagedChannel getChannel() throws UnconfiguredException {
    if ((channel != null) && !channel.isShutdown() && !channel.isTerminated()) {
      return channel;
    }
    shutdown();
    try {
      log.debug("creating a new channel");
      var channelCredentials = TlsChannelCredentials.newBuilder()
          .trustManager(new ByteArrayInputStream(getConfig().getRootCert().getBytes()))
          .keyManager(
              new ByteArrayInputStream(getConfig().getPublicCert().getBytes()),
              new ByteArrayInputStream(getConfig().getPrivateKey().getBytes()))
          .build();
      channel = Grpc.newChannelBuilderForAddress(
              getConfig().getApiHostname(), getConfig().getApiPort(), channelCredentials)
          .keepAliveTime(1, TimeUnit.SECONDS)
          .keepAliveTimeout(10, TimeUnit.SECONDS)
          .idleTimeout(30, TimeUnit.MINUTES)
          .overrideAuthority("exile-proxy")
          // TODO: add service configuration for retry
//          .defaultServiceConfig(null)
          .build();
      return channel;
    } catch (IOException e) {
      throw new UnconfiguredException("TCN Gate client is unconfigured", e);
    }
  }

  /**
   * Checks if the client is configured.
   * @return true if configured, false otherwise.
   */
  public boolean isConfigured() {
    if (event == null) {
      return false;
    }
    log.debug("isConfigured {}", !this.event.isUnconfigured());
    return !this.event.isUnconfigured();
  }

  /**
   * Retrieves the current status of the client.
   * @return A Map containing status information.
   */
  public Map<String,Object> getStatus() {
    if (!isConfigured()) {
      return Map.of(
          "status", "false",
          "configured", isConfigured()
          );
    }
     return Map.of(
         "running", "true",
         "configured", isConfigured(),
         "api_endpoint", this.event.getApiEndpoint(),
         "certificate_name", this.event.getCertificateName(),
         "org", this.event.getOrg(),
         "expiration_date", this.event.getExpirationDate(),
         "certificate_description", this.event.getCertificateDescription()
     );
  }

  /**
   * Retrieves a list of agents from the Gate service.
   * @return A List of Agent objects.
   * @throws UnconfiguredException if the client is not configured.
   */
  public List<Agent> listAgents() throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var ret = new ArrayList<Agent>();
    var res = client.listAgents(Service.ListAgentsReq.newBuilder().build());
    while (res.hasNext()) {
      var r = res.next();
      ret.add(new Agent(
            r.getAgent().getUserId(), 
            r.getAgent().getPartnerAgentId(),
            r.getAgent().getUsername(), 
            r.getAgent().getFirstName(), 
            r.getAgent().getLastName()
        ));
    }
    return ret;
  }

  /**
   * Sets the state of an agent.
   * @param agentId The ID of the agent.
   * @param state The new state to set.
   * @return The response from the Gate service.
   * @throws UnconfiguredException if the client is not configured.
   * @throws IllegalArgumentException if the state is invalid.
   */
  public Service.SetAgentStateRes setAgentState(String agentId, SetAgentState state) throws UnconfiguredException {
    tcnapi.exile.gate.v1.Entities.AgentState newState = null;
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());

    log.debug("setAgentState: agentId={}, state={}/{}", agentId, state, state.getValue());
    newState = tcnapi.exile.gate.v1.Entities.AgentState.forNumber(state.getValue());
    if (newState == null) {
      throw new IllegalArgumentException("Invalid state: " + state);
    }

    var res = client.setAgentState(Service.SetAgentStateReq.newBuilder().setUserId(agentId).setNewState(newState).build());
    return res;
  }

  /**
   * Retrieves the current state of an agent.
   * @param agentId The ID of the agent.
   * @return The response from the Gate service.
   * @throws UnconfiguredException if the client is not configured.
   */
  public Service.GetAgentStateRes getAgentState(String agentId) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var res = client.getAgentState(Service.GetAgentStateReq.newBuilder().setUserId(agentId).build());
    return res;
  }

  /**
   * Retrieves information about a specific agent.
   * @param pathParam The ID of the agent.
   * @return An Agent object.
   * @throws UnconfiguredException if the client is not configured.
   */
  public Agent getAgent(String pathParam) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var a = client.getAgent(Service.GetAgentReq.newBuilder().setUserId(pathParam).build());
    return new Agent(a.getAgent().getUserId(), a.getAgent().getFirstName(), a.getAgent().getLastName(), a.getAgent().getUsername(), a.getAgent().getPartnerAgentId());
  }

  /**
   * Retrieves information about an agent using their partner agent ID.
   * @param partnerAgentId The partner agent ID.
   * @return An Agent object.
   * @throws UnconfiguredException if the client is not configured.
   */
  public Agent getAgentByPartnerAgentId(String partnerAgentId) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var a = client.getAgentByPartnerId(Service.GetAgentByPartnerIdReq.newBuilder().setPartnerAgentId(partnerAgentId).build());
    return new Agent(a.getAgent().getUserId(), a.getAgent().getFirstName(), a.getAgent().getLastName(), a.getAgent().getUsername(), a.getAgent().getPartnerAgentId());
  }

  /**
   * Creates or updates an agent.
   * @param userId The ID of the agent.
   * @param username The username of the agent.
   * @param firstName The first name of the agent.
   * @param lastName The last name of the agent.
   * @param partnerAgentId The partner agent ID.
   * @param password The password for the agent.
   * @return An Agent object.
   * @throws UnconfiguredException if the client is not configured.
   * @throws RuntimeException as this method is currently unimplemented.
   */
  public Agent upsertAgent(@NotEmpty String userId, String username, String firstName, String lastName, String partnerAgentId, String password) throws UnconfiguredException {
    throw new RuntimeException("unimplemented");
  }

  /**
   * Retrieves the recording status of an agent.
   * @param userId The ID of the agent.
   * @return true if the agent is recording, false otherwise.
   * @throws UnconfiguredException if the client is not configured.
   */
  public boolean getAgentRecordingStatus(String userId) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var res = client.getRecording(Service.GetRecordingReq.newBuilder().setUserId(userId).build());
    return res.getRecording();
  }

  /**
   * Sets the recording status of an agent.
   * @param userId The ID of the agent.
   * @param recording true to start recording, false to stop.
   * @return The new recording status.
   * @throws UnconfiguredException if the client is not configured.
   */
  public boolean setAgentRecordingStatus(String userId, boolean recording) throws UnconfiguredException {
    // TODO: move the logic to the exile gate service
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    try {
      if (recording) {
        client.startRecording(Service.StartRecordingReq.newBuilder().setUserId(userId).build());
        return true;
      } else {
        client.stopRecording(Service.StopRecordingReq.newBuilder().setUserId(userId).build());
        return false;
      }
    } catch (Exception e) {
      if (e.getMessage().contains("already recording") && recording) {
        return true;
      }
      return false;
    }
  }

  /**
   * Initiates a manual dial for an agent.
   * @param userId The ID of the agent.
   * @param phoneNumber The phone number to dial.
   * @param callerId The caller ID to use.
   * @return A ManualDialResult object.
   * @throws UnconfiguredException if the client is not configured.
   */
  public ManualDialResult agentManualDial(String userId, String phoneNumber, String callerId, String poolId, String recordId) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var req = Service.DialReq.newBuilder().setUserId(userId).setPhoneNumber(phoneNumber);
    if ((callerId != null) && (!callerId.trim().isEmpty())) req = req.setCallerId(callerId.trim());

    if (poolId != null)  req.setPoolId(StringValue.of(poolId));
    if (recordId != null)  req.setRecordId(StringValue.of(recordId));

    var res = client.dial(req.build());

    return ManualDialResult.fromProto(res);
  }

  /**
   * Retrieves information about the organization.
   * @return An OrgInfo object.
   * @throws UnconfiguredException if the client is not configured.
   */
  public OrgInfo getOrg() throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var res = client.org(Service.OrgReq.newBuilder().build());
    return new OrgInfo(res.getOrgId(), res.getOrgName());
  }

  /**
   * Retrieves a list of scrub lists.
   * @return A List of ScrubList objects.
   * @throws UnconfiguredException if the client is not configured.
   */
  public List<ScrubList> listScrubLists() throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var res = client.listScrubLists(Service.ListScrubListsReq.newBuilder().build());
    var ret = new ArrayList<ScrubList>();
    for (var s : res.getScrubListList()) {
      ret.add(new ScrubList(s.getScrubListId(), s.getReadOnly(), ScrubListType.create(s.getContentType().getNumber())));
    }
    return ret;
  }

  /**
   * Updates an entry in a scrub list.
   * @param scrubListId The ID of the scrub list.
   * @param content The content to update.
   * @param expirationDate The expiration date of the entry.
   * @param notes Any notes for the entry.
   * @param countryCode The country code for the entry.
   * @throws UnconfiguredException if the client is not configured.
   */
  public void updateScrubListEntry(String scrubListId, @NotEmpty String content, @Nullable Date expirationDate, @Nullable String notes, @Nullable String countryCode) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var req = Service.UpdateScrubListEntryReq.newBuilder().setScrubListId(scrubListId).setContent(content);
    if (notes != null) {
      req.setNotes(StringValue.of(notes));
    }
    if (expirationDate != null) {
      var inst = expirationDate.toInstant();
      req.setExpirationDate(Timestamp.newBuilder().setSeconds(inst.getEpochSecond()).setNanos(inst.getNano()).build());
    }
    if (countryCode != null) {
      req.setCountryCode(StringValue.of(countryCode));
    }
    var res = client.updateScrubListEntry(req.build());
  }

  /**
   * Deletes entries from a scrub list.
   * @param scrubListId The ID of the scrub list.
   * @param content The content to delete.
   * @throws UnconfiguredException if the client is not configured.
   */
  public void deleteScrubListEntries(String scrubListId, String content) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    client.deleteScrubListEntries(Service.DeleteScrubListEntriesReq.newBuilder().setScrubListId(scrubListId).addContent(content).build());
  }

  /**
   * Adds an entry to a scrub list.
   * @param scrubListId The ID of the scrub list.
   * @param content The content to add.
   * @param expirationDate The expiration date of the entry.
   * @param notes Any notes for the entry.
   * @param countryCode The country code for the entry.
   * @throws UnconfiguredException if the client is not configured.
   */
  public void addScrubListEntry(String scrubListId, @NotEmpty String content, @Nullable Date expirationDate, @Nullable String notes, @Null String countryCode) throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    var req = Service.AddScrubListEntriesReq.newBuilder().setScrubListId(scrubListId);
    var reqScrubListEntry = Service.AddScrubListEntriesReq.ScrubListEntry.newBuilder().setContent(content);
    if (expirationDate != null) {
      var inst = expirationDate.toInstant();
      reqScrubListEntry.setExpirationDate(Timestamp.newBuilder().setSeconds(inst.getEpochSecond()).setNanos(inst.getNano()).build());
    }
    if (notes != null) {
      reqScrubListEntry.setNotes(StringValue.of(notes));
    }
    if (countryCode != null) {
      req.setCountryCode(countryCode);
    }
    req.addScrubListEntry(reqScrubListEntry);
    var res = client.addScrubListEntries(req.build());
  }
  protected Service.ConfigRes getExileConfig() throws UnconfiguredException {
    var client = ExileGateServiceGrpc.newBlockingStub(getChannel());
    return client
        .withDeadlineAfter(1L, TimeUnit.SECONDS)
        .config(Service.ConfigReq.newBuilder()
            .setDocker(true)
            .build());

  }
}
