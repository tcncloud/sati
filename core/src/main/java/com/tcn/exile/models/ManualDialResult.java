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

import build.buf.gen.tcnapi.exile.gate.v2.DialResponse;
import java.util.Objects;

public record ManualDialResult(
    String phoneNumber,
    String callerId,
    long callSid,
    String callType,
    String orgId,
    String partnerAgentId) {
  public ManualDialResult {

    Objects.nonNull(phoneNumber);
    Objects.nonNull(callerId);
    Objects.nonNull(callSid);

    Objects.nonNull(callType);
    Objects.nonNull(orgId);
    Objects.nonNull(partnerAgentId);
  }

  public static ManualDialResult fromProto(DialResponse result) {
    if (result == null) {
      throw new IllegalArgumentException("result cannot be null");
    }
    String callType = "";

    switch (result.getCallType()) {
      case CALL_TYPE_INBOUND:
        callType = "inbound";
        break;
      case CALL_TYPE_OUTBOUND:
        callType = "outbound";
        break;
      case CALL_TYPE_MANUAL:
        callType = "manual";
        break;
      case CALL_TYPE_MAC:
        callType = "outbound";
        break;
      case CALL_TYPE_PREVIEW:
        callType = "outbound";
        break;
      default:
        throw new IllegalArgumentException("Invalid call type: " + result.getCallType());
    }
    try {
      return new ManualDialResult(
          result.getPhoneNumber(),
          result.getCallerId(),
          Long.parseLong(result.getCallSid()),
          callType,
          result.getOrgId(),
          result.getPartnerAgentId());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid call_sid format: " + result.getCallSid(), e);
    }
  }
}
