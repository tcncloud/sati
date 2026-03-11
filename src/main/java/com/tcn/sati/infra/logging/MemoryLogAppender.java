package com.tcn.sati.infra.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Logback appender that captures log messages in memory for ListTenantLogs.
 * 
 * Uses the same pattern as the old MemoryAppender so the admin UI can parse
 * the log lines correctly: "HH:mm:ss.SSS LEVEL abbreviated.class - msg\n"
 * 
 * Configure in logback.xml:
 * <pre>
 * &lt;appender name="MEMORY" class="com.tcn.sati.infra.logging.MemoryLogAppender"/&gt;
 * &lt;root level="INFO"&gt;
 *     &lt;appender-ref ref="MEMORY"/&gt;
 * &lt;/root&gt;
 * </pre>
 */
public class MemoryLogAppender extends AppenderBase<ILoggingEvent> {
    
    private static final int MAX_EVENTS = 500;
    private static final String PATTERN = "%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n";
    
    private static final ConcurrentLinkedDeque<LogEntry> events = new ConcurrentLinkedDeque<>();
    
    private PatternLayout layout;
    
    @Override
    public void start() {
        layout = new PatternLayout();
        layout.setContext(getContext());
        layout.setPattern(PATTERN);
        layout.start();
        super.start();
    }
    
    @Override
    public void stop() {
        super.stop();
        if (layout != null) {
            layout.stop();
        }
    }
    
    @Override
    protected void append(ILoggingEvent event) {
        String formatted = layout.doLayout(event);
        events.addLast(new LogEntry(formatted, event.getTimeStamp()));
        
        // Trim oldest entries if over limit
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }
    
    /**
     * Get recent log messages (newest first).
     */
    public static List<String> getRecentLogs() {
        List<LogEntry> snapshot = new ArrayList<>(events);
        Collections.reverse(snapshot);
        List<String> result = new ArrayList<>(snapshot.size());
        for (LogEntry entry : snapshot) {
            result.add(entry.message);
        }
        return result;
    }
    
    /**
     * Get recent log messages (newest first), limited to N entries.
     */
    public static List<String> getRecentLogs(int limit) {
        List<String> all = getRecentLogs();
        return all.subList(0, Math.min(limit, all.size()));
    }
    
    /**
     * Get log messages within a time range (newest first).
     * 
     * @param startTimeMs start time in epoch millis (inclusive)
     * @param endTimeMs end time in epoch millis (inclusive)
     */
    public static List<String> getLogsInTimeRange(long startTimeMs, long endTimeMs) {
        List<String> result = new ArrayList<>();
        List<LogEntry> snapshot = new ArrayList<>(events);
        for (LogEntry entry : snapshot) {
            if (entry.timestampMs >= startTimeMs && entry.timestampMs <= endTimeMs) {
                result.add(entry.message);
            }
        }
        Collections.reverse(result); // newest first
        return result;
    }
    
    /**
     * Get number of stored logs.
     */
    public static int size() {
        return events.size();
    }
    
    /**
     * Clear all stored logs.
     */
    public static void clear() {
        events.clear();
    }
    
    /** A log entry with its formatted string and raw timestamp. */
    private static class LogEntry {
        final String message;
        final long timestampMs;
        
        LogEntry(String message, long timestampMs) {
            this.message = message;
            this.timestampMs = timestampMs;
        }
    }
}
