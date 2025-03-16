package com.tcn.exile.memlogger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class MemoryAppender extends AppenderBase<ILoggingEvent> {
  private static final int MAX_SIZE = 100;
  private static final LinkedList<ILoggingEvent> events = new LinkedList<ILoggingEvent>();
  private LogShipper shipper = null;

  @Override
  public void start() {
    super.start();
    MemoryAppenderInstance.setInstance(this);
  }

  @Override
  public void stop() {
    super.stop();
    MemoryAppenderInstance.setInstance(null);
  }

  @Override
  protected void append(ILoggingEvent event) {
    synchronized (events) {
      events.add(event);
      if (events.size() > MAX_SIZE) {
        if (shipper != null) {
          shipper.shipLogs(getEventsAsString());
          events.clear();
        } else {
          events.removeFirst();
        }
      }
    }
  }

  private String getEventsAsString() {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent e : events) {
      sb.append(String.format(
          "%s %s [%s] %s - %s%n",
          e.getInstant(),
          e.getLevel(),
          e.getThreadName(),
          e.getLoggerName(),
          e.getFormattedMessage()
      ));
    }
    return sb.toString();
  }


  public void enableLogShipper(LogShipper shipper) {
    addInfo("Log shipper enabled");
    if (this.shipper == null) this.shipper = shipper;
    synchronized (events) {
      shipper.shipLogs(getEventsAsString());
      events.clear();
    }

  }

  public void disableLogShipper() {
    addInfo("Log shipper disabled");
    this.shipper.stop();
    this.shipper = null;
  }


  public List<ILoggingEvent> getEvents() {
    var ret = new ArrayList<ILoggingEvent>(events);
    events.clear();
    return ret;
  }

  public void appendEvent(ILoggingEvent event) {
    append(event);
  }

}
