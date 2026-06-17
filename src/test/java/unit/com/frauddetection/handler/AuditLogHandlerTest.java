package com.frauddetection.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.config.JacksonConfig;
import com.frauddetection.model.BankAccount;
import com.frauddetection.model.Decision;
import com.frauddetection.model.RiskFactor;
import com.frauddetection.repository.DecisionEvent;
import com.frauddetection.repository.entity.DecisionAuditEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogHandlerTest {

    @Mock
    private DynamoDbTable<DecisionAuditEntity> auditTable;

    @Mock
    private S3Client s3Client;

    @Mock
    private Context lambdaContext;

    private ObjectMapper objectMapper;
    private AuditLogHandler handler;

    private static final String BUCKET_NAME = "test-audit-bucket";

    @BeforeEach
    void setUp() {
        objectMapper = JacksonConfig.createObjectMapper();
        handler = new AuditLogHandler(objectMapper, auditTable, s3Client, BUCKET_NAME);
    }

    @Test
    void handleRequest_successfulProcessing_persistsToDbAndS3() {
        // Arrange
        ScheduledEvent event = createScheduledEvent();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // Act
        String result = handler.handleRequest(event, lambdaContext);

        // Assert
        assertThat(result).isEqualTo("SUCCESS");
        verify(auditTable).putItem(any(DecisionAuditEntity.class));
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void mapToEntity_correctFieldMapping() {
        // Arrange
        DecisionEvent decisionEvent = createDecisionEvent();

        // Act
        DecisionAuditEntity entity = handler.mapToEntity(decisionEvent);

        // Assert
        assertThat(entity.getPk()).isEqualTo("AUDIT#msg-123");
        assertThat(entity.getSk()).isEqualTo("DECISION");
        assertThat(entity.getDebtorAccount()).isEqualTo("112233#12345678");
        assertThat(entity.getCreditorAccount()).isEqualTo("445566#87654321");
        assertThat(entity.getAmount()).isEqualTo(1500.00);
        assertThat(entity.getRiskScore()).isEqualTo(75);
        assertThat(entity.getDecision()).isEqualTo("REVIEW");
        assertThat(entity.getAmountScore()).isEqualTo(20);
        assertThat(entity.getCopScore()).isEqualTo(15);
        assertThat(entity.getBehaviouralScore()).isEqualTo(25);
        assertThat(entity.getChannelScore()).isEqualTo(15);
        assertThat(entity.getGsiPk()).isEqualTo("DECISION#REVIEW");
        assertThat(entity.getGsiSk()).isNotNull();
        assertThat(entity.getRiskFactors()).containsExactly("AMOUNT", "BEHAVIOURAL");
        assertThat(entity.getExplanations()).containsExactly(
                "Amount exceeds typical transaction range",
                "Unusual transaction time detected"
        );
    }

    @Test
    void buildS3Key_correctPattern() {
        // Arrange
        Instant timestamp = Instant.parse("2024-03-15T10:30:00Z");
        String messageId = "msg-456";

        // Act
        String key = handler.buildS3Key(messageId, timestamp);

        // Assert
        assertThat(key).isEqualTo("decisions/2024/03/15/msg-456.json");
    }

    @Test
    void handleRequest_dynamoDbFailure_retriesAndThrows() {
        // Arrange
        ScheduledEvent event = createScheduledEvent();
        doThrow(new RuntimeException("DynamoDB error"))
                .when(auditTable).putItem(any(DecisionAuditEntity.class));

        // Act & Assert
        assertThatThrownBy(() -> handler.handleRequest(event, lambdaContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process DecisionMade event");

        // Verify retry attempts (initial + 3 retries = 4 total calls)
        verify(auditTable, times(4)).putItem(any(DecisionAuditEntity.class));
    }

    @Test
    void handleRequest_s3Failure_retriesAndThrows() {
        // Arrange
        ScheduledEvent event = createScheduledEvent();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("S3 error"));

        // Act & Assert
        assertThatThrownBy(() -> handler.handleRequest(event, lambdaContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process DecisionMade event");

        // DynamoDB should succeed, S3 should be retried (initial + 3 retries = 4)
        verify(auditTable, times(1)).putItem(any(DecisionAuditEntity.class));
        verify(s3Client, times(4)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void handleRequest_dynamoDbRecoversOnRetry_succeeds() {
        // Arrange
        ScheduledEvent event = createScheduledEvent();
        doThrow(new RuntimeException("Transient error"))
                .doNothing()
                .when(auditTable).putItem(any(DecisionAuditEntity.class));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // Act
        String result = handler.handleRequest(event, lambdaContext);

        // Assert
        assertThat(result).isEqualTo("SUCCESS");
        verify(auditTable, times(2)).putItem(any(DecisionAuditEntity.class));
    }

    @Test
    void handleRequest_s3PutUsesCorrectBucketAndKey() {
        // Arrange
        ScheduledEvent event = createScheduledEvent();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // Act
        handler.handleRequest(event, lambdaContext);

        // Assert
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

        PutObjectRequest captured = requestCaptor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET_NAME);
        assertThat(captured.key()).startsWith("decisions/");
        assertThat(captured.key()).endsWith("/msg-123.json");
        assertThat(captured.contentType()).isEqualTo("application/json");
    }

    @Test
    void mapToEntity_nullRiskFactors_handledGracefully() {
        // Arrange
        DecisionEvent decisionEvent = new DecisionEvent(
                "msg-789",
                Instant.now(),
                new BankAccount("112233", "12345678", "John Doe"),
                new BankAccount("445566", "87654321", "Jane Doe"),
                BigDecimal.valueOf(500),
                30,
                Decision.ALLOW,
                10,
                5,
                10,
                5,
                null
        );

        // Act
        DecisionAuditEntity entity = handler.mapToEntity(decisionEvent);

        // Assert
        assertThat(entity.getRiskFactors()).isNull();
        assertThat(entity.getExplanations()).isNull();
    }

    private ScheduledEvent createScheduledEvent() {
        ScheduledEvent event = new ScheduledEvent();
        Map<String, Object> detail = new HashMap<>();
        detail.put("messageId", "msg-123");
        detail.put("timestamp", "2024-03-15T10:30:00Z");
        detail.put("debtorAccount", Map.of(
                "sortCode", "112233",
                "accountNumber", "12345678",
                "accountName", "John Doe"
        ));
        detail.put("creditorAccount", Map.of(
                "sortCode", "445566",
                "accountNumber", "87654321",
                "accountName", "Jane Doe"
        ));
        detail.put("amount", 1500.00);
        detail.put("riskScore", 75);
        detail.put("decision", "REVIEW");
        detail.put("amountScore", 20);
        detail.put("copScore", 15);
        detail.put("behaviouralScore", 25);
        detail.put("channelScore", 15);
        detail.put("riskFactors", List.of(
                Map.of("category", "AMOUNT", "explanation", "Amount exceeds typical transaction range"),
                Map.of("category", "BEHAVIOURAL", "explanation", "Unusual transaction time detected")
        ));
        event.setDetail(detail);
        return event;
    }

    private DecisionEvent createDecisionEvent() {
        return new DecisionEvent(
                "msg-123",
                Instant.parse("2024-03-15T10:30:00Z"),
                new BankAccount("112233", "12345678", "John Doe"),
                new BankAccount("445566", "87654321", "Jane Doe"),
                BigDecimal.valueOf(1500.00),
                75,
                Decision.REVIEW,
                20,
                15,
                25,
                15,
                List.of(
                        new RiskFactor("AMOUNT", "Amount exceeds typical transaction range"),
                        new RiskFactor("BEHAVIOURAL", "Unusual transaction time detected")
                )
        );
    }
}
