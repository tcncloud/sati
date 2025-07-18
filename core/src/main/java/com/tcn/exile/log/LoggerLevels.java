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
package com.tcn.exile.log;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.serde.annotation.Serdeable;
import java.util.HashMap;

@Serdeable
@ConfigurationProperties("logger")
public class LoggerLevels {
  private HashMap<String, String> levels = new HashMap<>();

  public HashMap<String, String> getLevels() {
    return levels;
  }

  public LoggerLevels setLevels(HashMap<String, String> levels) {
    this.levels = levels;
    return this;
  }
}
