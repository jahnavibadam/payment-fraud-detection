package com.frauddetection;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.config.JacksonConfig;
import com.frauddetection.handler.FraudDetectionHandler;
import com.frauddetection.model.*;
import com.frauddetection.repository.*;
import com.frauddetection.repository.entity.BeneficiaryRegistryEntity;
import com.frauddetection.repository.entity.CustomerProfileEntity;
import com.frauddetection.scoring.*;
import com.frauddetection.validation.RequestValidator;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for the fraud detection flow.
 * Uses Testcontainers with LocalStack to provide real DynamoDB and EventBridge services.
 *
 * Validates Requirements: 1.1, 5.1, 4.3, 6.6
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FraudDetectionE2ETest {

    private static final String TABLE_NAME = "FraudDetection";
    private static final String EVENT_BUS_NAME = "fraud-detection";
    private static final String SQS_QUEUE_NAME = "decision-events-queue";

    @Container
    private static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(
                    LocalStackContainer.Service.DYNAMODB,
                    LocalStackContainer.Service.SQS
            );

    private ObjectMapper objectMapper;
    private DynamoDbClient dynamoDbClient;
    private EventBridgeClient eventBridgeClient;
    private SqsClient sqsClient;
    private DynamoDbConfig dynamoDbConfig;
    private FraudDetectionHandler handler;
    private String sqsQueueUrl;
    private String sqsQueueArn;

    @BeforeAll
    void setUp() {
        objectMapper = JacksonConfig.createObjectMapper();

        // Create DynamoDB client pointing to LocalStack
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();

        // Create EventBridge client pointing to LocalStack (same endpoint for all services)
        eventBridgeClient = EventBridgeClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();

        // Create SQS client for EventBridge verification (same endpoint for all services)
        sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();

        // Set up DynamoDB table
        dynamoDbConfig = new DynamoDbConfig(dynamoDbClient, TABLE_NAME);
        dynamoDbConfig.createTable();

        // Set up EventBridge bus and SQS queue as target
        setupEventBridgeWithSqsTarget();

        // Create handler with real LocalStack-backed dependencies
        RequestValidator requestValidator = new RequestValidator();
        RiskScoringEngine riskScoringEngine = new RiskScoringEngine(
                new AmountScorer(), new CopScorer(), new BehaviouralScorer(), new ChannelScorer());
        DecisionEngine decisionEngine = new DecisionEngine();
        ConcurrencyGuard concurrencyGuard = new ConcurrencyGuard();
        CustomerProfileRepository customerProfileRepo = new DynamoDbCustomerProfileRepository(dynamoDbConfig);
        BeneficiaryRegistryRepository beneficiaryRegistryRepo = new DynamoDbBeneficiaryRegistryRepository(dynamoDbConfig);
        EventPublisher eventPublisher = new EventBridgeEventPublisher(eventBridgeClient, objectMapper, EVENT_BUS_NAME);

        handler = new FraudDetectionHandler(
                objectMapper,
                requestValidator,
                riskScoringEngine,
                decisionEngine,
                concurrencyGuard,
                customerProfileRepo,
                beneficiaryRegistryRepo,
                eventPublisher
        );
    }

    @BeforeEach
    void purgeQueue() {
        // Drain SQS queue before each test to isolate events
        drainQueue();
    }

    @AfterAll
    void tearDown() {
        if (dynamoDbClient != null) dynamoDbClient.close();
        if (eventBridgeClient != null) eventBridgeClient.close();
        if (sqsClient != null) sqsClient.close();
    }

    // --- Test: Full end-to-end flow with known customer profile (ALLOW decision) ---

    @Test
    @DisplayName("E2E: Valid request with known customer and matching CoP results in ALLOW decision")
    void fullFlow_knownCustomer_matchingCop_resultsInAllow() throws Exception {
        // Arrange: Seed a customer profile with established history
        seedCustomerProfile("123456", "12345678", 200.0, 50.0, 50,
                List.of("device-001"), List.of("51.5074,-0.1278"), 120000.0);

        FasterPaymentRequest request = buildRequest(
                "msg-e2e-001", "123456", "12345678", "654321", "87654321",
                new BigDecimal("150.00"), CopResult.MATCH, ChannelType.MOBILE,
                "device-001", new GeoLocation(51.5074, -0.1278), Duration.ofMinutes(2)
        );

        // Act
        APIGatewayProxyResponseEvent response = invokeHandler(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision = objectMapper.readValue(response.getBody(), FraudDecisionResponse.class);
        assertThat(decision.messageId()).isEqualTo("msg-e2e-001");
        assertThat(decision.decision()).isEqualTo(Decision.ALLOW);
        assertThat(decision.riskScore()).isLessThanOrEqualTo(30);
        assertThat(decision.breakdown()).isNotNull();
    }

    // --- Test: Full flow with high-risk beneficiary triggers BLOCK ---

    @Test
    @DisplayName("E2E: Request to HIGH_RISK beneficiary results in BLOCK decision")
    void fullFlow_highRiskBeneficiary_resultsInBlock() throws Exception {
        // Arrange: Seed customer profile and high-risk beneficiary
        seedCustomerProfile("111111", "11111111", 100.0, 20.0, 30,
                List.of("device-100"), List.of("51.5074,-0.1278"), 90000.0);
        seedBeneficiaryStatus("222222", "22222222", BeneficiaryFlag.HIGH_RISK);

        FasterPaymentRequest request = buildRequest(
                "msg-e2e-002", "111111", "11111111", "222222", "22222222",
                new BigDecimal("50.00"), CopResult.MATCH, ChannelType.ONLINE_BANKING,
                "device-100", new GeoLocation(51.5074, -0.1278), Duration.ofMinutes(2)
        );

        // Act
        APIGatewayProxyResponseEvent response = invokeHandler(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision = objectMapper.readValue(response.getBody(), FraudDecisionResponse.class);
        assertThat(decision.messageId()).isEqualTo("msg-e2e-002");
        assertThat(decision.decision()).isEqualTo(Decision.BLOCK);
        assertThat(decision.riskScore()).isGreaterThanOrEqualTo(71);
    }

    // --- Test: Full flow with MULE_LINKED beneficiary always BLOCKs ---

    @Test
    @DisplayName("E2E: Request to MULE_LINKED beneficiary always results in BLOCK")
    void fullFlow_muleLinkedBeneficiary_alwaysBlocks() throws Exception {
        // Arrange
        seedCustomerProfile("333333", "33333333", 200.0, 50.0, 50,
                List.of("device-200"), List.of("51.5074,-0.1278"), 120000.0);
        seedBeneficiaryStatus("444444", "44444444", BeneficiaryFlag.MULE_LINKED);

        FasterPaymentRequest request = buildRequest(
                "msg-e2e-003", "333333", "33333333", "444444", "44444444",
                new BigDecimal("10.00"), CopResult.MATCH, ChannelType.MOBILE,
                "device-200", new GeoLocation(51.5074, -0.1278), Duration.ofMinutes(3)
        );

        // Act
        APIGatewayProxyResponseEvent response = invokeHandler(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision = objectMapper.readValue(response.getBody(), FraudDecisionResponse.class);
        assertThat(decision.decision()).isEqualTo(Decision.BLOCK);
    }

    // --- Test: DynamoDB reads return correct customer profiles ---

    @Test
    @DisplayName("E2E: DynamoDB returns correct customer profile and uses it for scoring")
    void dynamoDbReads_returnCorrectCustomerProfile() throws Exception {
        // Arrange: Seed profile with specific values
        seedCustomerProfile("555555", "55555555", 1000.0, 200.0, 100,
                List.of("trusted-device"), List.of("48.8566,2.3522"), 300000.0);

        FasterPaymentRequest request = buildRequest(
                "msg-e2e-004", "555555", "55555555", "666666", "66666666",
                new BigDecimal("900.00"), CopResult.MATCH, ChannelType.MOBILE,
                "trusted-device", new GeoLocation(48.8566, 2.3522), Duration.ofMinutes(5)
        );

        // Act
        APIGatewayProxyResponseEvent response = invokeHandler(request);

        // Assert: The amount (900) is within mean+3σ (1000+600=1600), so amount score is 0.
        // With MATCH CoP, known device, known location, and normal session → low risk → ALLOW
        assertThat(response.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision = objectMapper.readValue(response.getBody(), FraudDecisionResponse.class);
        assertThat(decision.decision()).isEqualTo(Decision.ALLOW);
        assertThat(decision.riskScore()).isLessThanOrEqualTo(30);
    }

    // --- Test: DynamoDB reads return correct beneficiary flags ---

    @Test
    @DisplayName("E2E: DynamoDB returns correct beneficiary flags for scoring decisions")
    void dynamoDbReads_returnCorrectBeneficiaryFlags() throws Exception {
        // Arrange: No beneficiary flag seeded → defaults to NONE
        seedCustomerProfile("777777", "77777777", 500.0, 100.0, 20,
                List.of("device-300"), List.of("51.5074,-0.1278"), 60000.0);

        FasterPaymentRequest request = buildRequest(
                "msg-e2e-005", "777777", "77777777", "888888", "88888888",
                new BigDecimal("100.00"), CopResult.MATCH, ChannelType.MOBILE,
                "device-300", new GeoLocation(51.5074, -0.1278), Duration.ofSeconds(45)
        );

        // Act
        APIGatewayProxyResponseEvent response = invokeHandler(request);

        // Assert: NONE beneficiary, amount within range, MATCH CoP → ALLOW
        assertThat(response.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision = objectMapper.readValue(response.getBody(), FraudDecisionResponse.class);
        // NONE flag does not trigger any override
        assertThat(decision.decision()).isIn(Decision.ALLOW, Decision.REVIEW);
    }

    // --- Test: EventBridge receives DecisionMade events ---

    @Test
    @DisplayName("E2E: EventBridge receives DecisionMade event after processing")
    void eventBridge_receivesDecisionMadeEvent() throws Exception {
        // Arrange
        seedCustomerProfile("999999", "99999999", 300.0, 80.0, 40,
                List.of("device-400"), List.of("51.5074,-0.1278"), 90000.0);

        FasterPaymentRequest request = buildRequest(
                "msg-e2e-006", "999999", "99999999", "111222", "11122233",
                new BigDecimal("200.00"), CopResult.MATCH, ChannelType.ONLINE_BANKING,
                "device-400", new GeoLocation(51.5074, -0.1278), Duration.ofMinutes(2)
        );

        // Act
        invokeHandler(request);

        // Wait for async event publishing
        Thread.sleep(2000);

        // Assert: Check SQS queue for the event
        ReceiveMessageResponse messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(sqsQueueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(5)
                .build());

        assertThat(messages.messages()).isNotEmpty();

        // Verify the event contains the correct message ID
        boolean foundEvent = messages.messages().stream()
                .anyMatch(msg -> msg.body().contains("msg-e2e-006"));
        assertThat(foundEvent).isTrue();
    }

    // --- Test: Latency is within 300ms SLA ---

    @Test
    @DisplayName("E2E: Handler processes request within 300ms SLA")
    void latency_withinSla() throws Exception {
        // Arrange: Seed profile so we don't measure cold-start effects
        seedCustomerProfile("100100", "10010010", 500.0, 100.0, 60,
                List.of("device-500"), List.of("51.5074,-0.1278"), 150000.0);

        FasterPaymentRequest request = buildRequest(
                "msg-e2e-007", "100100", "10010010", "200200", "20020020",
                new BigDecimal("300.00"), CopResult.MATCH, ChannelType.MOBILE,
                "device-500", new GeoLocation(51.5074, -0.1278), Duration.ofMinutes(3)
        );

        // Warm up: invoke once to ensure JIT/classloading is done
        invokeHandler(request);

        // Act: measure latency over multiple invocations
        long totalMs = 0;
        int iterations = 5;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            APIGatewayProxyResponseEvent response = invokeHandler(request);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            totalMs += elapsed;
            assertThat(response.getStatusCode()).isEqualTo(200);
        }

        long avgMs = totalMs / iterations;
        assertThat(avgMs).as("Average handler latency should be under 300ms").isLessThan(300);
    }

    // --- Test: Invalid request returns validation errors ---

    @Test
    @DisplayName("E2E: Invalid request body returns 400 with validation errors")
    void invalidRequest_returnsValidationErrors() throws Exception {
        // Arrange: Request with invalid sort code and missing amount
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "messageId", "msg-invalid",
                "debtorAccount", Map.of("sortCode", "12", "accountNumber", "12345678", "accountName", "Test"),
                "creditorAccount", Map.of("sortCode", "654321", "accountNumber", "87654321", "accountName", "Payee"),
                "currency", "GBP",
                "channel", Map.of("type", "MOBILE", "deviceId", "dev-1")
        ));

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(invalidBody);

        Context context = mockContext();

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.getBody()).contains("errors");
    }

    // --- Test: Unknown customer gets default profile and still processes ---

    @Test
    @DisplayName("E2E: Unknown customer without profile still produces a valid decision")
    void unknownCustomer_producesValidDecision() throws Exception {
        // Arrange: No profile seeded for this customer
        FasterPaymentRequest request = buildRequest(
                "msg-e2e-008", "000000", "00000000", "111111", "11111112",
                new BigDecimal("100.00"), CopResult.MATCH, ChannelType.MOBILE,
                "new-device", new GeoLocation(51.5074, -0.1278), Duration.ofMinutes(1)
        );

        // Act
        APIGatewayProxyResponseEvent response = invokeHandler(request);

        // Assert: Should still return a valid 200 response with a decision
        assertThat(response.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision = objectMapper.readValue(response.getBody(), FraudDecisionResponse.class);
        assertThat(decision.messageId()).isEqualTo("msg-e2e-008");
        assertThat(decision.decision()).isNotNull();
        assertThat(decision.breakdown()).isNotNull();
    }

    // ===== Helper Methods =====

    private void setupEventBridgeWithSqsTarget() {
        // Create EventBridge event bus
        eventBridgeClient.createEventBus(CreateEventBusRequest.builder()
                .name(EVENT_BUS_NAME)
                .build());

        // Create SQS queue to receive events
        CreateQueueResponse queueResponse = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName(SQS_QUEUE_NAME)
                .build());
        sqsQueueUrl = queueResponse.queueUrl();

        // Get queue ARN
        GetQueueAttributesResponse attrs = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(sqsQueueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build());
        sqsQueueArn = attrs.attributes().get(QueueAttributeName.QUEUE_ARN);

        // Create EventBridge rule to forward all DecisionMade events to SQS
        eventBridgeClient.putRule(PutRuleRequest.builder()
                .name("decision-made-to-sqs")
                .eventBusName(EVENT_BUS_NAME)
                .eventPattern("""
                        {
                            "source": ["com.frauddetection"],
                            "detail-type": ["DecisionMade"]
                        }
                        """)
                .state(RuleState.ENABLED)
                .build());

        // Add SQS as target
        eventBridgeClient.putTargets(PutTargetsRequest.builder()
                .rule("decision-made-to-sqs")
                .eventBusName(EVENT_BUS_NAME)
                .targets(Target.builder()
                        .id("sqs-target")
                        .arn(sqsQueueArn)
                        .build())
                .build());
    }

    private void seedCustomerProfile(String sortCode, String accountNumber,
                                     double meanAmount, double stdDevAmount, int txCount90d,
                                     List<String> devices, List<String> locations, double avgSessionDuration) {
        CustomerProfileEntity entity = new CustomerProfileEntity();
        entity.setPk(CustomerProfileEntity.buildPk(sortCode, accountNumber));
        entity.setSk(CustomerProfileEntity.SK_VALUE);
        entity.setMeanAmount(meanAmount);
        entity.setStdDevAmount(stdDevAmount);
        entity.setTransactionCount90d(txCount90d);
        entity.setDevices(devices);
        entity.setLocations(locations);
        entity.setAvgSessionDuration(avgSessionDuration);
        entity.setLastUpdated(Instant.now().toString());

        dynamoDbConfig.customerProfileTable().putItem(entity);
    }

    private void seedBeneficiaryStatus(String sortCode, String accountNumber, BeneficiaryFlag flag) {
        BeneficiaryRegistryEntity entity = new BeneficiaryRegistryEntity();
        entity.setPk(BeneficiaryRegistryEntity.buildPk(sortCode, accountNumber));
        entity.setSk(BeneficiaryRegistryEntity.SK_VALUE);
        entity.setFlag(flag.name());
        entity.setLastUpdated(Instant.now().toString());
        entity.setReason("Integration test setup");

        dynamoDbConfig.beneficiaryRegistryTable().putItem(entity);
    }

    private FasterPaymentRequest buildRequest(String messageId, String debtorSortCode, String debtorAccountNumber,
                                              String creditorSortCode, String creditorAccountNumber,
                                              BigDecimal amount, CopResult copResult, ChannelType channelType,
                                              String deviceId, GeoLocation geoLocation, Duration sessionDuration) {
        return new FasterPaymentRequest(
                messageId,
                new BankAccount(debtorSortCode, debtorAccountNumber, "Debtor Name"),
                new BankAccount(creditorSortCode, creditorAccountNumber, "Creditor Name"),
                amount,
                "GBP",
                "Test Payment",
                new ConfirmationOfPayee(copResult, "Creditor Name"),
                new Channel(channelType, deviceId, geoLocation, sessionDuration),
                Instant.now()
        );
    }

    private APIGatewayProxyResponseEvent invokeHandler(FasterPaymentRequest request) throws Exception {
        String body = objectMapper.writeValueAsString(request);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(body);
        Context context = mockContext();
        return handler.handleRequest(event, context);
    }

    private Context mockContext() {
        Context context = mock(Context.class);
        when(context.getFunctionName()).thenReturn("FraudDetectionHandler");
        when(context.getRemainingTimeInMillis()).thenReturn(30000);
        return context;
    }

    private void drainQueue() {
        try {
            ReceiveMessageResponse messages;
            do {
                messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(sqsQueueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)
                        .build());
                for (Message msg : messages.messages()) {
                    sqsClient.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(sqsQueueUrl)
                            .receiptHandle(msg.receiptHandle())
                            .build());
                }
            } while (!messages.messages().isEmpty());
        } catch (Exception ignored) {
            // Best effort drain
        }
    }
}
