package com.tcn.exile.gateclients.v2;

import com.tcn.exile.gateclients.UnconfiguredException;
import com.tcn.exile.models.OrgInfo;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tcnapi.exile.gate.v2.GateServiceGrpc;
import tcnapi.exile.gate.v2.Public;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GateClientTest {

    @Mock
    private ManagedChannel channel;

    @Mock
    private GateServiceGrpc.GateServiceBlockingStub blockingStub;

    private GateClient gateClient;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        gateClient = new TestGateClient(channel);
        when(blockingStub.withDeadlineAfter(anyLong(), any())).thenReturn(blockingStub);
        when(blockingStub.withWaitForReady()).thenReturn(blockingStub);
    }

    @Test
    void getOrganizationInfo_Success() {
        // Arrange
        var response = Public.GetOrganizationInfoResponse.newBuilder()
            .setOrgName("Test Org")
            .build();
        when(blockingStub.getOrganizationInfo(any())).thenReturn(response);

        // Act
        OrgInfo result = gateClient.getOrganizationInfo();

        // Assert
        assertNotNull(result);
        assertEquals("Test Org", result.getName());
        verify(blockingStub).getOrganizationInfo(any());
    }

    @Test
    void submitJobResults_Success() {
        // Arrange
        var request = Public.SubmitJobResultsRequest.newBuilder()
            .setJobId("test-job-id")
            .build();
        var response = Public.SubmitJobResultsResponse.newBuilder().build();
        when(blockingStub.submitJobResults(request)).thenReturn(response);

        // Act
        var result = gateClient.submitJobResults(request);

        // Assert
        assertNotNull(result);
        verify(blockingStub).submitJobResults(request);
    }

    // @Test
    // void getAgentStatus_ThrowsException_WhenUnconfigured() {
    //     // Arrange
    //     doThrow(new UnconfiguredException("Test exception"))
    //         .when(blockingStub)
    //         .getAgentStatus(any(Public.GetAgentStatusRequest.class));

    //     // Act & Assert
    //     var exception = assertThrows(RuntimeException.class, () -> 
    //         gateClient.getAgentStatus(Public.GetAgentStatusRequest.newBuilder().build())
    //     );
    //     assertTrue(exception.getCause() instanceof UnconfiguredException);
    // }

    // Test implementation of GateClient that allows us to mock the stub
    private class TestGateClient extends GateClient {
        private final ManagedChannel testChannel;

        TestGateClient(ManagedChannel channel) {
            this.testChannel = channel;
        }

        @Override
        public ManagedChannel getChannel() throws UnconfiguredException {
            return testChannel;
        }

        @Override
        protected GateServiceGrpc.GateServiceBlockingStub getStub() throws UnconfiguredException {
            return blockingStub;
        }
    }
} 