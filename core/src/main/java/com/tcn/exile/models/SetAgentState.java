/*
 *  (C) 2017-2024 TCN Inc. All rights reserved.
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

public enum SetAgentState {
  //  UNAVALIABLE(0),
  //  IDLE(1),
  READY(2),
  HUNGUP(3),
  //  DESTROYED(4),
  //  PEERED(5),
  PAUSED(6),
  WRAPUP(7),
//  PREPARING_AFTER_IDLE(8),
//  PREPARING_AFTER_WRAPUP(9),
//  PREPARING_AFTER_PAUSE(10),
//  PREPARING_AFTER_DIAL_CANCEL(11),
//  PREPARING_AFTER_PBX_REJECT(12),
//  PREPARING_AFTER_PBX_HANGUP(13),
//  PREPARING_AFTER_PBX_WAS_TAKEN(14),
//  PREPARING_AFTER_GUI_BUSY(15),
//  MANUAL_DIAL_PREPARED(16),
//  PREVIEW_DIAL_PREPARED(17),
//  MANUAL_DIAL_STARTED(18),
//  PREVIEW_DIAL_STARTED(19),
//  OUTBOUND_LOCKED(20),
//  WARM_AGENT_TRANSFER_STARTED_SOURCE(21),
//  WARM_AGENT_TRANSFER_STARTED_DESTINATION(22),
//  WARM_OUTBOUND_TRANSFER_STARTED(23),
//  WARM_OUTBOUND_TRANSFER_PEER_LOST(24),
//  PBX_POPUP_LOCKED(25),
//  PEERED_WITH_CALL_ON_HOLD(26),
//  CALLBACK_RESUMING(27),
//  GUI_BUSY(28),
//  INTERCOM(29),
//  INTERCOM_RINGING_SOURCE(30),
//  INTERCOM_RINGING_DESTINATION(31),
//  WARM_OUTBOUND_TRANSFER_OUTBOUND_LOST(32),
//  PREPARED_TO_PEER(33),
//  WARM_SKILL_TRANSFER_SOURCE_PENDING(34),
//  CALLER_TRANSFER_STARTED(35),
//  CALLER_TRANSFER_LOST_PEER(36),
//  CALLER_TRANSFER_LOST_MERGED_CALLER(37),
//  COLD_OUTBOUND_TRANSFER_STARTED(38),
//  COLD_AGENT_TRANSFER_STARTED(39),
;
  private int value;

  SetAgentState(int i) {
    value = i;
  }

  public int getValue() {
    return value;
  }
}
