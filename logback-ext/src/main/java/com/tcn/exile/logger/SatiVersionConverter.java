/*
 *  (C) 2017-2026 TCN Inc. All rights reserved.
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
package com.tcn.exile.logger;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter that emits the sati library version on every log line so deployments
 * remain self-identifying after truncation, filtering, or concatenation. Register via the companion
 * {@code logback-sati.xml} include resource and reference as {@code %satiVersion} in your encoder
 * pattern.
 */
public final class SatiVersionConverter extends ClassicConverter {
  @Override
  public String convert(ILoggingEvent event) {
    return Version.VALUE;
  }
}
