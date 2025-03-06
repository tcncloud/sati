package com.tcn.exile.memlogger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MemoryAppenderTest {

    private MemoryAppender memoryAppender;

    @BeforeEach
    public void setUp() {
        memoryAppender = new MemoryAppender();
        memoryAppender.start();
    }

    @Test
    public void testAppendAndRetrieveEvents() {
        ILoggingEvent event1 = mock(ILoggingEvent.class);
        ILoggingEvent event2 = mock(ILoggingEvent.class);

        memoryAppender.append(event1);
        memoryAppender.append(event2);

        List<ILoggingEvent> events = memoryAppender.getEvents();
        assertEquals(2, events.size());
        assertTrue(events.contains(event1));
        assertTrue(events.contains(event2));
    }

    @Test
    public void testMaxSize() {
        for (int i = 0; i < 1001; i++) {
            ILoggingEvent event = mock(ILoggingEvent.class);
            memoryAppender.append(event);
        }

        List<ILoggingEvent> events = memoryAppender.getEvents();
        assertEquals(1000, events.size());
    }

    @Test
    public void testGetEventsClearsList() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        memoryAppender.append(event);

        List<ILoggingEvent> events = memoryAppender.getEvents();
        assertEquals(1, events.size());

        events = memoryAppender.getEvents();
        assertEquals(0, events.size());
    }
}