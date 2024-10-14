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

package com.tcn.exile.models;

import io.micronaut.context.event.ApplicationEvent;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Objects;

@Serdeable
public class PluginConfigEvent extends ApplicationEvent {
  private String orgId;
  private String orgName;
  private String configurationName;
  private String configurationPayload;
  private boolean unconfigured = Boolean.TRUE;

  public String getOrgId() {
    return orgId;
  }

  public PluginConfigEvent setOrgId(String orgId) {
    this.orgId = orgId;
    return this;
  }

  public String getOrgName() {
    return orgName;
  }

  public boolean isUnconfigured() {
    return this.unconfigured;
  }

  public PluginConfigEvent setUnconfigured(boolean unconfigured) {
    this.unconfigured = unconfigured;
    return this;
  }

  public PluginConfigEvent setOrgName(String orgName) {
    this.orgName = orgName;
    return this;
  }

  public String getConfigurationName() {
    return configurationName;
  }

  public PluginConfigEvent setConfigurationName(String configurationName) {
    this.configurationName = configurationName;
    return this;
  }

  public String getConfigurationPayload() {
    return configurationPayload;
  }

  public PluginConfigEvent setConfigurationPayload(String configurationPayload) {
    this.configurationPayload = configurationPayload;
    return this;
  }

  /**
   * Constructs a prototypical Event.
   *
   * @param source The object on which the Event initially occurred.
   * @throws IllegalArgumentException if source is null.
   */
  public PluginConfigEvent(Object source) {
    super(source);
  }

  @Override
  public String toString() {
    return "PluginConfigEvent{" +
        "orgId='" + orgId + '\'' +
        ", orgName='" + orgName + '\'' +
        ", configurationName='" + configurationName + '\'' +
        ", configurationPayload='" + configurationPayload + '\'' +
        ", unconfigured=" + unconfigured +
        '}';
  }

  public boolean equals(PluginConfigEvent o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PluginConfigEvent that = (PluginConfigEvent) o;
    return Objects.equals(orgId, that.orgId)
        && Objects.equals(orgName, that.orgName)
        && Objects.equals(configurationName, that.configurationName)
        && Objects.equals(configurationPayload, that.configurationPayload)
        && Objects.equals(unconfigured, that.unconfigured);
  }

  public int hashCode() {
    return Objects.hash(orgId, orgName, configurationName, configurationPayload, unconfigured);
  }
}
