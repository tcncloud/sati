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

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum CallType {
  inbound(0),
  outbound(1),
  preview(2),
  manual(3),
  mac(4);

  private final int value;

  CallType(int i) {
    value = i;
  }

  public int getValue() {
    return value;
  }
}
