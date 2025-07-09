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
package com.tcn.exile.demo.single;

import com.tcn.exile.config.DiagnosticsService;
import com.tcn.exile.models.DiagnosticsResult;
import com.tcn.exile.models.TenantLogsResult;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/api/admin")
@Requires(bean = Environment.class)
@OpenAPIDefinition(tags = {@Tag(name = "admin")})
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  @Inject private DiagnosticsService diagnosticsService;
  @Inject private ConfigChangeWatcher configChangeWatcher;

  /**
   * Returns system diagnostics information.
   *
   * @return DiagnosticsResult containing system diagnostics
   */
  @Get("/diagnostics")
  @Tag(name = "admin")
  @Produces(MediaType.APPLICATION_JSON)
  public HttpResponse<DiagnosticsResult> getDiagnostics() {
    log.info("Collecting diagnostics information");
    try {
      // First try to call printDiagnosticsToTerminal which might fail but shows detailed error info
      diagnosticsService.printDiagnosticsToTerminal();

      // Then get the serializable diagnostics result
      DiagnosticsResult result = diagnosticsService.collectSerdeableDiagnostics();
      return HttpResponse.ok(result);
    } catch (Exception e) {
      log.error("Failed to collect diagnostics information", e);
      throw new HttpStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to collect diagnostics: " + e.getMessage());
    }
  }

  /**
   * Returns tenant logs from memory appender.
   *
   * @return TenantLogsResult containing tenant logs
   */
  @Get("/listTenantLogs")
  @Tag(name = "admin")
  @Produces(MediaType.APPLICATION_JSON)
  public HttpResponse<TenantLogsResult> getListTenantLogs() {
    log.info("Collecting tenant logs information");
    try {
      // Get the serializable tenant logs result
      TenantLogsResult result = diagnosticsService.collectSerdeableTenantLogs();
      return HttpResponse.ok(result);
    } catch (Exception e) {
      log.error("Failed to collect tenant logs information", e);
      throw new HttpStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to collect tenant logs: " + e.getMessage());
    }
  }
}
