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
package com.tcn.exile.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tcn.exile.model.CallType;
import com.tcn.exile.model.DataRecord;
import com.tcn.exile.model.Filter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobHandlerPopAccountTest {

  @Test
  void overloadDelegatesToHandlerImplementingOnlyTheOriginalSignature() throws Exception {
    var handler =
        new JobHandler() {
          @Override
          public DataRecord popAccount(
              String orgId,
              String poolId,
              String recordId,
              String partnerAgentId,
              long callSid,
              CallType callType,
              List<Filter> filters) {
            return new DataRecord(poolId, recordId, Map.of());
          }
        };

    var record =
        handler.popAccount(
            "O-1",
            "P-1",
            "R-1",
            "PA-1",
            42L,
            CallType.MANUAL,
            List.of(),
            Optional.of(CallType.INBOUND));

    assertEquals("P-1", record.poolId());
    assertEquals("R-1", record.recordId());
  }

  @Test
  void overloadStillThrowsWhenNeitherSignatureIsImplemented() {
    var handler = new JobHandler() {};

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            handler.popAccount(
                "O-1", "P-1", "R-1", "PA-1", 42L, CallType.MANUAL, List.of(), Optional.empty()));
  }
}
