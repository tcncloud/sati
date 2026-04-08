package com.tcn.exile.demo;

import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs all received events. In a real integration, these would be persisted to a database. */
public class DemoEventHandler implements EventHandler {

  private static final Logger log = LoggerFactory.getLogger(DemoEventHandler.class);

  @Override
  public void onAgentCall(AgentCallEvent event) {
    log.info(
        "AgentCall: callSid={} type={} agent={} talk={}s",
        event.callSid(),
        event.callType(),
        event.partnerAgentId(),
        event.talkDuration().toSeconds());
  }

  @Override
  public void onTelephonyResult(TelephonyResultEvent event) {
    log.info(
        "TelephonyResult: callSid={} type={} status={} outcome={} phone={}",
        event.callSid(),
        event.callType(),
        event.status(),
        event.outcomeCategory(),
        event.phoneNumber());
  }

  @Override
  public void onAgentResponse(AgentResponseEvent event) {
    log.info(
        "AgentResponse: callSid={} agent={} key={} value={}",
        event.callSid(),
        event.partnerAgentId(),
        event.responseKey(),
        event.responseValue());
  }

  @Override
  public void onTransferInstance(TransferInstanceEvent event) {
    log.info(
        "TransferInstance: id={} type={} result={}",
        event.transferInstanceId(),
        event.transferType(),
        event.transferResult());
  }

  @Override
  public void onCallRecording(CallRecordingEvent event) {
    log.info(
        "CallRecording: id={} callSid={} duration={}s",
        event.recordingId(),
        event.callSid(),
        event.duration().toSeconds());
  }

  @Override
  public void onTask(TaskEvent event) {
    log.info(
        "Task: sid={} pool={} record={} status={}",
        event.taskSid(),
        event.poolId(),
        event.recordId(),
        event.status());
  }
}
