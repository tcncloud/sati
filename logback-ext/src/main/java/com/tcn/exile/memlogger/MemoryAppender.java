package com.tcn.exile.memlogger;

import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;

public class MemoryAppender extends OutputStreamAppender<ILoggingEvent> {
    private static final int MAX_SIZE = 100;
    private static final LinkedList<String> events = new LinkedList<String>();
    private LogShipper shipper = null;


    @Override
    public void setOutputStream(OutputStream outputStream) {
        super.setOutputStream(outputStream);
    }

    @Override
    public void start() {
        OutputStream targetStream = new MemoryOutputStream();
        // enable jansi only if withJansi set to true
        setOutputStream(targetStream);
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
        if (!isStarted()) {
            return;
        }
        subAppend(event);
        synchronized (events) {
            events.add(new String(this.encoder.encode(event)));
            if (events.size() > MAX_SIZE) {
                if (shipper != null) {
                    shipper.shipLogs(getEventsAsList());
                    events.clear();
                } else {
                    events.removeFirst();
                }
            }
        }
    }

    public List<String> getEventsAsList() {
        return events;
    }

    public void enableLogShipper(LogShipper shipper) {
        addInfo("Log shipper enabled");
        if (this.shipper == null)
            this.shipper = shipper;
        synchronized (events) {
            shipper.shipLogs(events);
            events.clear();
        }

    }

    public void disableLogShipper() {
        addInfo("Log shipper disabled");
        this.shipper.stop();
        this.shipper = null;
    }

    public List<String> getEvents() {
        return events;
    }

    public void clearEvents() {
        events.clear();
    }

    public void appendEvent(ILoggingEvent event) {
        append(event);
    }

}
