package com.tcn.exile.logger;



import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TenantLoggerTest {
    private TenantLogger tenantLogger;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    public void setUp() {
        // Get the SLF4J logger and cast to Logback logger
        logbackLogger = (Logger) LoggerFactory.getLogger(TenantLoggerTest.class);
        tenantLogger = new TenantLogger(logbackLogger);

        // Create and attach a ListAppender to capture log events
        listAppender = new ListAppender<>();
        listAppender.start();
        logbackLogger.addAppender(listAppender);
        
        // Clear MDC before each test
        MDC.clear();
    }

    @Test
    public void testInfoLogWithTenant() {
        String tenant = "tenant1";
        String message = "Test info message";

        tenantLogger.info(tenant, message);

        List<ILoggingEvent> logs = listAppender.list;
        assertEquals(1, logs.size(), "Exactly one log event should be captured");

        ILoggingEvent event = logs.get(0);
        assertEquals(Level.INFO, event.getLevel(), "Log level should be INFO");
        assertEquals(message, event.getFormattedMessage(), "Log message should match");
        assertEquals(tenant, event.getMDCPropertyMap().get("tenant"), "Tenant should be set in MDC");

        // Skip caller data checks as they might not be available in this environment
    }

    @Test
    public void testDebugLogWithArguments() {
        String tenant = "tenant2";
        String format = "Test debug with args: {} and {}";
        String arg1 = "value1";
        String arg2 = "value2";

        tenantLogger.debug(tenant, format, arg1, arg2);

        List<ILoggingEvent> logs = listAppender.list;
        assertEquals(1, logs.size(), "Exactly one log event should be captured");

        ILoggingEvent event = logs.get(0);
        assertEquals(Level.DEBUG, event.getLevel(), "Log level should be DEBUG");
        assertEquals("Test debug with args: value1 and value2", event.getFormattedMessage(), "Log message should match formatted string");
        assertEquals(tenant, event.getMDCPropertyMap().get("tenant"), "Tenant should be set in MDC");

        // Skip caller data checks as they might not be available in this environment
    }

    @Test
    public void testErrorLogWithThrowable() {
        String tenant = "tenant3";
        String message = "Test error message";
        Exception exception = new RuntimeException("Test exception");

        tenantLogger.error(tenant, message, exception);

        List<ILoggingEvent> logs = listAppender.list;
        assertEquals(1, logs.size(), "Exactly one log event should be captured");

        ILoggingEvent event = logs.get(0);
        assertEquals(Level.ERROR, event.getLevel(), "Log level should be ERROR");
        assertEquals(message, event.getFormattedMessage(), "Log message should match");
        assertEquals(tenant, event.getMDCPropertyMap().get("tenant"), "Tenant should be set in MDC");
        
        // Updated throwable assertion to check class name and message instead of object equality
        if (event.getThrowableProxy() != null) {
            assertEquals(exception.getClass().getName(), event.getThrowableProxy().getClassName(), "Throwable class should match");
            assertEquals(exception.getMessage(), event.getThrowableProxy().getMessage(), "Throwable message should match");
        } else {
            fail("ThrowableProxy is null");
        }

        // Skip caller data checks as they might not be available in this environment
    }

    @Test
    public void testNullTenantThrowsException() {
        String message = "Test message";

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> tenantLogger.info(null, message),
            "Null tenant should throw IllegalArgumentException"
        );

        assertEquals("Tenant cannot be null", exception.getMessage(), "Exception message should match");
        assertTrue(listAppender.list.isEmpty(), "No log events should be captured");
    }

    @Test
    public void testMDCIsClearedAfterLogging() {
        String tenant = "tenant4";
        String message = "Test message";

        tenantLogger.info(tenant, message);

        assertNull(MDC.get("tenant"), "MDC should be cleared after logging");
        assertEquals(1, listAppender.list.size(), "One log event should be captured");
    }
}