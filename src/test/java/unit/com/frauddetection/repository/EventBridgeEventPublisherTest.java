package com.frauddetection.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.config.JacksonConfig;
import com.frauddetection.model.BankAccount;
import com.frauddetection.model.Decision;
import com.frauddetection.model.RiskFactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventBridgeEventPublisherTest {

    private EventBridgeClient eventBridgeClient;
    private ObjectMapper objectMapper;
    private EventBridgeEventPublisher publisher;

    @BeforeEach
    void setUp() {
        eventBridgeClient = mock(EventBridgeClient.class);
        objectMapper = JacksonConfig.createObjectMapper();
        publisher = new EventBridgeEventPublisher(eventBridgeClient, objectMapper, "test-bus");
    }

    @Test
    void publishDecisionMade_sendsEventToEventBridge() throws Exception {
        // Given
        PutEventsResponse successResponse = PutEventsResponse.builder()
            .failedEntryCount(0)
            .entries(PutEventsResultEntry.builder().build())
            .build();
        when(eventBridgeClient.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse);

        DecisionEvent event = createDecisionEvent();

        // When
        publisher.publishDecisionMade(event);

        // Wait for async execution
        Thread.sleep(500);

        // Then
        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(eventBridgeClient).putEvents(captor.capture());

        PutEventsRequest captured = captor.getValue();
        assertThat(captured.entries()).hasSize(1);

        PutEventsRequestEntry entry = captured.entries().get(0);
        assertThat(entry.eventBusName()).isEqualTo("test-bus");
        assertThat(entry.source()).isEqualTo("com.frauddetection");
        assertThat(entry.detailType()).isEqualTo("DecisionMade");
        assertThat(entry.detail()).contains("\"messageId\":\"msg-123\"");
        assertThat(entry.detail()).contains("\"decision\":\"BLOCK\"");
        assertThat(entry.detail()).contains("\"riskScore\":85");
    }

    @Test
    void publishDecisionMade_doesNotBlockOnFailure() throws Exception {
        // Given - EventBridge client throws an exception
        when(eventBridgeClient.putEvents(any(PutEventsRequest.class)))
            .thenThrow(new RuntimeException("Network error"));

        DecisionEvent event = createDecisionEvent();

        // When - should not throw, fire-and-forget
        long start = System.currentTimeMillis();
        publisher.publishDecisionMade(event);
        long elapsed = System.currentTimeMillis() - start;

        // Then - returns almost immediately (fire-and-forget)
        assertThat(elapsed).isLessThan(100);

        // Wait for async execution to complete
        Thread.sleep(500);
    }

    @Test
    void publishDecisionMade_handlesFailedEntries() throws Exception {
        // Given - EventBridge returns a failure entry
        PutEventsResponse failResponse = PutEventsResponse.builder()
            .failedEntryCount(1)
            .entries(PutEventsResultEntry.builder()
                .errorCode("InternalError")
                .errorMessage("Service unavailable")
                .build())
            .build();
        when(eventBridgeClient.putEvents(any(PutEventsRequest.class))).thenReturn(failResponse);

        DecisionEvent event = createDecisionEvent();

        // When - should not throw
        publisher.publishDecisionMade(event);

        // Wait for async execution
        Thread.sleep(500);

        // Then - still called EventBridge (failure is logged, not re-thrown)
        verify(eventBridgeClient).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishDecisionMade_serializesAllRequiredFields() throws Exception {
        // Given
        PutEventsResponse successResponse = PutEventsResponse.builder()
            .failedEntryCount(0)
            .entries(PutEventsResultEntry.builder().build())
            .build();
        when(eventBridgeClient.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse);

        DecisionEvent event = createDecisionEvent();

        // When
        publisher.publishDecisionMade(event);
        Thread.sleep(500);

        // Then
        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(eventBridgeClient).putEvents(captor.capture());

        String detail = captor.getValue().entries().get(0).detail();

        // Verify all required fields from Requirement 6.1 are present
        assertThat(detail).contains("\"messageId\"");
        assertThat(detail).contains("\"timestamp\"");
        assertThat(detail).contains("\"riskScore\"");
        assertThat(detail).contains("\"decision\"");
        assertThat(detail).contains("\"amountScore\"");
        assertThat(detail).contains("\"copScore\"");
        assertThat(detail).contains("\"behaviouralScore\"");
        assertThat(detail).contains("\"channelScore\"");
        assertThat(detail).contains("\"riskFactors\"");
    }

    private DecisionEvent createDecisionEvent() {
        return new DecisionEvent(
            "msg-123",
            Instant.parse("2024-01-15T10:30:00Z"),
            new BankAccount("123456", "12345678", "John Doe"),
            new BankAccount("654321", "87654321", "Jane Smith"),
            new BigDecimal("1500.00"),
            85,
            Decision.BLOCK,
            20,
            25,
            15,
            25,
            List.of(
                new RiskFactor("amount", "Amount exceeds 3 standard deviations above mean"),
                new RiskFactor("cop", "Name mismatch on payee confirmation")
            )
        );
    }
}
