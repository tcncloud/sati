package com.tcn.exile.handler;

import com.tcn.exile.model.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Handles jobs dispatched by the gate server. Each method receives plain Java parameters and
 * returns plain Java types. Proto conversion is handled internally by the library.
 *
 * <p>Methods run on virtual threads — blocking I/O (JDBC, HTTP) is fine.
 *
 * <p>Default implementations throw {@link UnsupportedOperationException}. Integrations override
 * only the jobs they handle and declare those as capabilities at registration.
 */
public interface JobHandler {

  default List<Pool> listPools() throws Exception {
    throw new UnsupportedOperationException("listPools not implemented");
  }

  default Pool getPoolStatus(String poolId) throws Exception {
    throw new UnsupportedOperationException("getPoolStatus not implemented");
  }

  default Page<DataRecord> getPoolRecords(String poolId, String pageToken, int pageSize)
      throws Exception {
    throw new UnsupportedOperationException("getPoolRecords not implemented");
  }

  default Page<DataRecord> searchRecords(List<Filter> filters, String pageToken, int pageSize)
      throws Exception {
    throw new UnsupportedOperationException("searchRecords not implemented");
  }

  default List<Field> getRecordFields(String poolId, String recordId, List<String> fieldNames)
      throws Exception {
    throw new UnsupportedOperationException("getRecordFields not implemented");
  }

  default boolean setRecordFields(String poolId, String recordId, List<Field> fields)
      throws Exception {
    throw new UnsupportedOperationException("setRecordFields not implemented");
  }

  default String createPayment(String poolId, String recordId, Map<String, Object> paymentData)
      throws Exception {
    throw new UnsupportedOperationException("createPayment not implemented");
  }

  default DataRecord popAccount(String poolId, String recordId) throws Exception {
    throw new UnsupportedOperationException("popAccount not implemented");
  }

  default Map<String, Object> executeLogic(String logicName, Map<String, Object> parameters)
      throws Exception {
    throw new UnsupportedOperationException("executeLogic not implemented");
  }

  /** Return client info. Keys: appName, appVersion, plus any custom metadata. */
  default Map<String, Object> info() throws Exception {
    throw new UnsupportedOperationException("info not implemented");
  }

  default void shutdown(String reason) throws Exception {
    throw new UnsupportedOperationException("shutdown not implemented");
  }

  default void processLog(String payload) throws Exception {
    throw new UnsupportedOperationException("processLog not implemented");
  }

  /** Return system diagnostics as structured sections. */
  default DiagnosticsInfo diagnostics() throws Exception {
    throw new UnsupportedOperationException("diagnostics not implemented");
  }

  default Page<LogEntry> listTenantLogs(
      Instant startTime, Instant endTime, String pageToken, int pageSize) throws Exception {
    throw new UnsupportedOperationException("listTenantLogs not implemented");
  }

  default void setLogLevel(String loggerName, String level) throws Exception {
    throw new UnsupportedOperationException("setLogLevel not implemented");
  }

  record DiagnosticsInfo(
      Map<String, Object> systemInfo,
      Map<String, Object> runtimeInfo,
      Map<String, Object> databaseInfo,
      Map<String, Object> custom) {}

  record LogEntry(Instant timestamp, String level, String logger, String message) {}
}
