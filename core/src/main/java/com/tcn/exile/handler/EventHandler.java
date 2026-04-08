package com.tcn.exile.handler;

import tcnapi.exile.types.v3.*;

/**
 * Handles events dispatched by the gate server. Events are informational — the server only needs an
 * acknowledgment, not a result.
 *
 * <p>If a method throws, the work item is nacked and will be redelivered.
 *
 * <p>Methods run on virtual threads — blocking I/O is fine.
 *
 * <p>Default implementations are no-ops. Override only what you need.
 */
public interface EventHandler {

  default void onAgentCall(AgentCall call) throws Exception {}

  default void onTelephonyResult(TelephonyResult result) throws Exception {}

  default void onAgentResponse(AgentResponse response) throws Exception {}

  default void onTransferInstance(TransferInstance transfer) throws Exception {}

  default void onCallRecording(CallRecording recording) throws Exception {}

  default void onTask(Task task) throws Exception {}
}
