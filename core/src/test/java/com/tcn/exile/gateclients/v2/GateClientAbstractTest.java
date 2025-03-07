package com.tcn.exile.gateclients.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tcn.exile.config.ConfigEvent;
import com.tcn.exile.gateclients.UnconfiguredException;

import io.grpc.ManagedChannel;

class GateClientAbstractTest {

    private GateClientAbstract gateClient;
    private ConfigEvent mockConfigEvent;
    private Logger log = LoggerFactory.getLogger(GateClientAbstractTest.class);

    @BeforeEach
    void setUp() {
        log.info("setUp()");
        gateClient = Mockito.mock(GateClientAbstract.class, Mockito.CALLS_REAL_METHODS);
        mockConfigEvent = Mockito.mock(ConfigEvent.class);
    }

    @Test
    void testSupports() {
        assertTrue(gateClient.supports(mockConfigEvent));
    }

    @Test
    void testOnApplicationEvent() {
        gateClient.onApplicationEvent(mockConfigEvent);
        verify(gateClient, times(1)).shutdown();
        // verify(gateClient, times(1)).start();
    }

    @Test
    void testShutdown() throws InterruptedException {
        ManagedChannel mockChannel = Mockito.mock(ManagedChannel.class);
        when(mockChannel.isShutdown()).thenReturn(false);
        when(mockChannel.isTerminated()).thenReturn(false);
        gateClient.channel = mockChannel;

        gateClient.shutdown();
        verify(mockChannel, times(1)).shutdown();
        verify(mockChannel, times(1)).awaitTermination(30, TimeUnit.SECONDS);
    }

    @Test
    void testGetChannelUnconfigured() {
        assertThrows(UnconfiguredException.class, () -> gateClient.getChannel());
    }

    @Test
    void testGetConfigUnconfigured() {
        assertThrows(UnconfiguredException.class, () -> gateClient.getConfig());
    }

    @Test
    void testGetConfig() throws UnconfiguredException {
        when(mockConfigEvent.isUnconfigured()).thenReturn(false);
        gateClient.onApplicationEvent(mockConfigEvent);
        assertEquals(mockConfigEvent, gateClient.getConfig());
    }
}
