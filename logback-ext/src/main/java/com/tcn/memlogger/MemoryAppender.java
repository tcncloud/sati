package com.tcn.memlogger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class MemoryAppender extends AppenderBase<ILoggingEvent> {
    private static final int MAX_SIZE = 1000;
    private static final LinkedList<ILoggingEvent> events = new LinkedList<ILoggingEvent>();

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
    synchronized(events) {
        events.add(event);
        if (events.size() > MAX_SIZE) {
            events.removeFirst();
        }
    }
   }


   public List<ILoggingEvent> getEvents() {
    synchronized(events) {
        var ret = new ArrayList<ILoggingEvent>(events);
        events.clear();
        return ret;
    }
   }

   public void appendEvent(ILoggingEvent event) {
    append(event);
   }

}
