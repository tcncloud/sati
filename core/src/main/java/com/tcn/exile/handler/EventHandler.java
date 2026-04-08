package com.tcn.exile.handler;

import com.tcn.exile.model.event.*;

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

  default void onAgentCall(AgentCallEvent event) throws Exception {}

  default void onTelephonyResult(TelephonyResultEvent event) throws Exception {}

  default void onAgentResponse(AgentResponseEvent event) throws Exception {}

  default void onTransferInstance(TransferInstanceEvent event) throws Exception {}

  default void onCallRecording(CallRecordingEvent event) throws Exception {}

  default void onTask(TaskEvent event) throws Exception {}
}
