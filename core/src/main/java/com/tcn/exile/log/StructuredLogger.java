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

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Utility class for structured logging with standardized message formats. Provides methods for
 * logging with consistent context and formatting.
 */
public class StructuredLogger {
  private final Logger logger;
  private final String component;

  /**
   * Creates a new StructuredLogger for the specified class.
   *
   * @param clazz The class to create a logger for
   */
  public StructuredLogger(Class<?> clazz) {
    this.logger = LoggerFactory.getLogger(clazz);
    this.component = clazz.getSimpleName();
  }

  /**
   * Creates a new StructuredLogger with the specified name.
   *
   * @param name The name for the logger
   */
  public StructuredLogger(String name) {
    this.logger = LoggerFactory.getLogger(name);
    this.component = name;
  }

  /**
   * Sets up the MDC context for a request.
   *
   * @param tenant The tenant ID
   * @param userId The user ID (optional)
   * @param operation The operation name (optional)
   * @param jobId The job ID (optional)
   */
  public static void setupRequestContext(
      String tenant, String userId, String operation, String jobId) {
    MDC.put("tenant", tenant);
    MDC.put("requestId", UUID.randomUUID().toString());

    if (userId != null) {
      MDC.put("userId", userId);
    }

    if (operation != null) {
      MDC.put("operation", operation);
    }

    if (jobId != null) {
      MDC.put("jobId", jobId);
    }
  }

  /** Clears the MDC context. */
  public static void clearContext() {
    MDC.clear();
  }

  /**
   * Logs a message with the specified category and level.
   *
   * @param category The log category
   * @param level The log level
   * @param action The action being performed
   * @param message The log message
   * @param args The message arguments
   */
  private void log(
      LogCategory category, String level, String action, String message, Object... args) {
    String formattedMessage =
        String.format(
            "[%s] [%s] - %s",
            category.getValue(), action, args.length > 0 ? String.format(message, args) : message);

    switch (level) {
      case "DEBUG":
        logger.debug(formattedMessage);
        break;
      case "INFO":
        logger.info(formattedMessage);
        break;
      case "WARN":
        logger.warn(formattedMessage);
        break;
      case "ERROR":
        logger.error(formattedMessage);
        break;
      default:
        logger.info(formattedMessage);
    }
  }

  /**
   * Logs an info message.
   *
   * @param category The log category
   * @param action The action being performed
   * @param message The log message
   * @param args The message arguments
   */
  public void info(LogCategory category, String action, String message, Object... args) {
    log(category, "INFO", action, message, args);
  }

  /**
   * Logs a debug message.
   *
   * @param category The log category
   * @param action The action being performed
   * @param message The log message
   * @param args The message arguments
   */
  public void debug(LogCategory category, String action, String message, Object... args) {
    log(category, "DEBUG", action, message, args);
  }

  /**
   * Logs a warning message.
   *
   * @param category The log category
   * @param action The action being performed
   * @param message The log message
   * @param args The message arguments
   */
  public void warn(LogCategory category, String action, String message, Object... args) {
    log(category, "WARN", action, message, args);
  }

  /**
   * Logs an error message.
   *
   * @param category The log category
   * @param action The action being performed
   * @param message The log message
   * @param args The message arguments
   */
  public void error(LogCategory category, String action, String message, Object... args) {
    log(category, "ERROR", action, message, args);
  }

  /**
   * Logs an error message with an exception.
   *
   * @param category The log category
   * @param action The action being performed
   * @param message The log message
   * @param throwable The exception
   */
  public void error(LogCategory category, String action, String message, Throwable throwable) {
    String formattedMessage = String.format("[%s] [%s] - %s", category.getValue(), action, message);
    logger.error(formattedMessage, throwable);
  }

  /**
   * Logs a performance metric.
   *
   * @param metricName The name of the metric
   * @param value The metric value
   * @param unit The unit of measurement
   */
  public void metric(String metricName, double value, String unit) {
    String formattedMessage =
        String.format(
            "[%s] [METRIC] - %s: %.2f %s",
            LogCategory.PERFORMANCE.getValue(), metricName, value, unit);
    logger.info(formattedMessage);
  }
}
