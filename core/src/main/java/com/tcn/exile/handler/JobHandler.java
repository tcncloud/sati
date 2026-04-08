package com.tcn.exile.handler;

import tcnapi.exile.worker.v3.*;

/**
 * Handles jobs dispatched by the gate server. Each method receives a task payload and returns the
 * corresponding result. The {@link com.tcn.exile.internal.WorkStreamClient} sends the result back
 * to the server automatically.
 *
 * <p>Implementations should throw an exception if the job cannot be processed. The stream client
 * will submit an {@code ErrorResult} with the exception message and nack the work item.
 *
 * <p>Methods run on virtual threads — blocking I/O (JDBC, HTTP) is fine.
 *
 * <p>Default implementations throw {@link UnsupportedOperationException}. Integrations override
 * only the jobs they handle and declare those as capabilities at registration.
 */
public interface JobHandler {

  default ListPoolsResult listPools(ListPoolsTask task) throws Exception {
    throw new UnsupportedOperationException("listPools not implemented");
  }

  default GetPoolStatusResult getPoolStatus(GetPoolStatusTask task) throws Exception {
    throw new UnsupportedOperationException("getPoolStatus not implemented");
  }

  default GetPoolRecordsResult getPoolRecords(GetPoolRecordsTask task) throws Exception {
    throw new UnsupportedOperationException("getPoolRecords not implemented");
  }

  default SearchRecordsResult searchRecords(SearchRecordsTask task) throws Exception {
    throw new UnsupportedOperationException("searchRecords not implemented");
  }

  default GetRecordFieldsResult getRecordFields(GetRecordFieldsTask task) throws Exception {
    throw new UnsupportedOperationException("getRecordFields not implemented");
  }

  default SetRecordFieldsResult setRecordFields(SetRecordFieldsTask task) throws Exception {
    throw new UnsupportedOperationException("setRecordFields not implemented");
  }

  default CreatePaymentResult createPayment(CreatePaymentTask task) throws Exception {
    throw new UnsupportedOperationException("createPayment not implemented");
  }

  default PopAccountResult popAccount(PopAccountTask task) throws Exception {
    throw new UnsupportedOperationException("popAccount not implemented");
  }

  default ExecuteLogicResult executeLogic(ExecuteLogicTask task) throws Exception {
    throw new UnsupportedOperationException("executeLogic not implemented");
  }

  default InfoResult info(InfoTask task) throws Exception {
    throw new UnsupportedOperationException("info not implemented");
  }

  default ShutdownResult shutdown(ShutdownTask task) throws Exception {
    throw new UnsupportedOperationException("shutdown not implemented");
  }

  default LoggingResult logging(LoggingTask task) throws Exception {
    throw new UnsupportedOperationException("logging not implemented");
  }

  default DiagnosticsResult diagnostics(DiagnosticsTask task) throws Exception {
    throw new UnsupportedOperationException("diagnostics not implemented");
  }

  default ListTenantLogsResult listTenantLogs(ListTenantLogsTask task) throws Exception {
    throw new UnsupportedOperationException("listTenantLogs not implemented");
  }

  default SetLogLevelResult setLogLevel(SetLogLevelTask task) throws Exception {
    throw new UnsupportedOperationException("setLogLevel not implemented");
  }
}
