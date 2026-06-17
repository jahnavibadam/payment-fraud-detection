package com.frauddetection.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.config.JacksonConfig;
import com.frauddetection.model.RiskFactor;
import com.frauddetection.repository.DecisionEvent;
import com.frauddetection.repository.DynamoDbConfig;
import com.frauddetection.repository.entity.DecisionAuditEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AWS Lambda handler that consumes DecisionMade events from EventBridge,
 * persists decision records to DynamoDB, and archives them to S3 for long-term storage.
 * <p>
 * Implements retry logic with exponential backoff (up to 3 retries) for both
 * DynamoDB writes and S3 puts.
 * <p>
 * Environment variables:
 * - FRAUD_DETECTION_TABLE: DynamoDB table name (default: "FraudDetection")
 * - AUDIT_BUCKET_NAME: S3 bucket for long-term archive storage
 */
public class AuditLogHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogHandler.class);

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 100;

    private static final String AUDIT_BUCKET_ENV = "AUDIT_BUCKET_NAME";

    private final ObjectMapper objectMapper;
    private final DynamoDbTable<DecisionAuditEntity> auditTable;
    private final S3Client s3Client;
    private final String bucketName;

    /**
     * Default constructor used by AWS Lambda runtime.
     * Initializes DynamoDB and S3 clients from environment configuration.
     */
    public AuditLogHandler() {
        this.objectMapper = JacksonConfig.createObjectMapper();

        DynamoDbClient dynamoDbClient = DynamoDbClient.create();
        DynamoDbConfig config = new DynamoDbConfig(dynamoDbClient);
        this.auditTable = config.decisionAuditTable();

        this.s3Client = S3Client.create();
        this.bucketName = System.getenv(AUDIT_BUCKET_ENV);
    }

    /**
     * Constructor for testing, allowing injection of dependencies.
     */
    public AuditLogHandler(ObjectMapper objectMapper,
                           DynamoDbTable<DecisionAuditEntity> auditTable,
                           S3Client s3Client,
                           String bucketName) {
        this.objectMapper = objectMapper;
        this.auditTable = auditTable;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        logger.info("Processing DecisionMade event");

        try {
            // Deserialize the event detail into DecisionEvent
            Map<String, Object> detail = event.getDetail();
            String detailJson = objectMapper.writeValueAsString(detail);
            DecisionEvent decisionEvent = objectMapper.readValue(detailJson, DecisionEvent.class);

            logger.info("Processing decision for messageId: {}", decisionEvent.messageId());

            // Map DecisionEvent to DecisionAuditEntity
            DecisionAuditEntity entity = mapToEntity(decisionEvent);

            // Persist to DynamoDB with retry
            persistToDynamoDb(entity);

            // Archive to S3 with retry
            archiveToS3(decisionEvent);

            logger.info("Successfully processed decision for messageId: {}", decisionEvent.messageId());
            return "SUCCESS";

        } catch (Exception e) {
            logger.error("Failed to process DecisionMade event", e);
            throw new RuntimeException("Failed to process DecisionMade event: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a DecisionEvent to a DecisionAuditEntity for DynamoDB persistence.
     */
    DecisionAuditEntity mapToEntity(DecisionEvent decisionEvent) {
        DecisionAuditEntity entity = new DecisionAuditEntity();

        entity.setPk(DecisionAuditEntity.buildPk(decisionEvent.messageId()));
        entity.setSk(DecisionAuditEntity.SK_VALUE);

        String timestampIso = decisionEvent.timestamp().toString();
        entity.setTimestamp(timestampIso);

        entity.setDebtorAccount(formatAccount(decisionEvent.debtorAccount()));
        entity.setCreditorAccount(formatAccount(decisionEvent.creditorAccount()));

        entity.setAmount(decisionEvent.amount().doubleValue());
        entity.setRiskScore(decisionEvent.riskScore());
        entity.setDecision(decisionEvent.decision().name());

        entity.setAmountScore(decisionEvent.amountScore());
        entity.setCopScore(decisionEvent.copScore());
        entity.setBehaviouralScore(decisionEvent.behaviouralScore());
        entity.setChannelScore(decisionEvent.channelScore());

        // Extract risk factor categories and explanations
        List<RiskFactor> riskFactors = decisionEvent.riskFactors();
        if (riskFactors != null) {
            entity.setRiskFactors(
                riskFactors.stream()
                    .map(RiskFactor::category)
                    .collect(Collectors.toList())
            );
            entity.setExplanations(
                riskFactors.stream()
                    .map(RiskFactor::explanation)
                    .collect(Collectors.toList())
            );
        }

        // GSI attributes
        entity.setGsiPk(DecisionAuditEntity.buildGsiPk(decisionEvent.decision().name()));
        entity.setGsiSk(timestampIso);

        return entity;
    }

    /**
     * Persists the entity to DynamoDB with retry logic (up to 3 retries with exponential backoff).
     */
    void persistToDynamoDb(DecisionAuditEntity entity) {
        executeWithRetry("DynamoDB put", () -> {
            auditTable.putItem(entity);
        });
    }

    /**
     * Archives the decision event to S3 as JSON with retry logic.
     * S3 key pattern: decisions/{year}/{month}/{day}/{messageId}.json
     */
    void archiveToS3(DecisionEvent decisionEvent) {
        executeWithRetry("S3 put", () -> {
            try {
                String key = buildS3Key(decisionEvent.messageId(), decisionEvent.timestamp());
                String json = objectMapper.writeValueAsString(decisionEvent);

                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("application/json")
                        .build();

                s3Client.putObject(putRequest, RequestBody.fromString(json));

                logger.info("Archived decision to S3: {}", key);
            } catch (Exception e) {
                throw new RuntimeException("S3 archive failed", e);
            }
        });
    }

    /**
     * Builds the S3 key using the pattern: decisions/{year}/{month}/{day}/{messageId}.json
     */
    String buildS3Key(String messageId, Instant timestamp) {
        DateTimeFormatter yearFormatter = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);
        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("dd").withZone(ZoneOffset.UTC);

        String year = yearFormatter.format(timestamp);
        String month = monthFormatter.format(timestamp);
        String day = dayFormatter.format(timestamp);

        return String.format("decisions/%s/%s/%s/%s.json", year, month, day, messageId);
    }

    /**
     * Formats a BankAccount as sortCode#accountNumber.
     */
    private String formatAccount(com.frauddetection.model.BankAccount account) {
        if (account == null) {
            return "";
        }
        return account.sortCode() + "#" + account.accountNumber();
    }

    /**
     * Executes an action with retry logic: up to 3 retries with exponential backoff.
     * Base delay is 100ms, doubling on each retry (100ms, 200ms, 400ms).
     */
    void executeWithRetry(String operationName, Runnable action) {
        int attempt = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt > MAX_RETRIES) {
                    logger.error("{} failed after {} retries", operationName, MAX_RETRIES, e);
                    throw new RuntimeException(
                            operationName + " failed after " + MAX_RETRIES + " retries: " + e.getMessage(), e);
                }
                long delay = BASE_DELAY_MS * (1L << (attempt - 1)); // exponential backoff
                logger.warn("{} attempt {} failed, retrying in {}ms", operationName, attempt, delay, e);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }
}
