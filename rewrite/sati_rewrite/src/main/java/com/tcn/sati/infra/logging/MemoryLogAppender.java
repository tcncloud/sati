package com.tcn.sati.infra.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Logback appender that captures log messages in memory for dashboard display.
 * 
 * Automatically captures all log.info(), log.warn(), log.error(), etc. calls
 * when configured in logback.xml.
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
    private static final DateTimeFormatter TIME_FMT = 
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    
    private static final ConcurrentLinkedDeque<String> events = new ConcurrentLinkedDeque<>();
    
    @Override
    protected void append(ILoggingEvent event) {
        String formatted = String.format("[%s] %-5s %s - %s",
            TIME_FMT.format(Instant.ofEpochMilli(event.getTimeStamp())),
            event.getLevel().toString(),
            shortenLogger(event.getLoggerName()),
            event.getFormattedMessage()
        );
        
        events.addLast(formatted);
        
        // Trim oldest entries if over limit
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }
    
    private static String shortenLogger(String name) {
        if (name == null) return "";
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(lastDot + 1) : name;
    }
    
    /**
     * Get recent log messages (newest first).
     */
    public static List<String> getRecentLogs() {
        List<String> result = new ArrayList<>(events);
        Collections.reverse(result);
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
}
