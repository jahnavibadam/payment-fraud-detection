package com.frauddetection;

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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that beneficiary flag updates propagate to
 * subsequent payment evaluations within 5 seconds (Requirement 4.3).
 *
 * Uses LocalStack via Testcontainers for a real DynamoDB instance.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BeneficiaryPropagationTest {

    @Container
    static LocalStackContainer localStack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.4"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private static DynamoDbClient dynamoDbClient;
    private static DynamoDbConfig dynamoDbConfig;
    private static FraudDetectionHandler handler;
    private static ObjectMapper objectMapper;

    private static final String TABLE_NAME = "FraudDetection";
    private static final String DEBTOR_SORT_CODE = "123456";
    private static final String DEBTOR_ACCOUNT_NUMBER = "12345678";
    private static final String CREDITOR_SORT_CODE = "654321";
    private static final String CREDITOR_ACCOUNT_NUMBER = "87654321";

    @BeforeAll
    static void setUp() {
        // Create DynamoDB client pointing to LocalStack
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localStack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
                .region(Region.of(localStack.getRegion()))
                .build();

        dynamoDbConfig = new DynamoDbConfig(dynamoDbClient, TABLE_NAME);

        // Create the FraudDetection table
        dynamoDbConfig.createTable();

        // Insert a customer profile for the debtor (established customer with history)
        insertCustomerProfile();

        // Insert beneficiary with flag=NONE initially
        insertBeneficiaryWithFlag("NONE");

        // Build the handler with real DynamoDB repos and no-op EventPublisher
        objectMapper = JacksonConfig.createObjectMapper();
        RequestValidator requestValidator = new RequestValidator();
        AmountScorer amountScorer = new AmountScorer();
        CopScorer copScorer = new CopScorer();
        BehaviouralScorer behaviouralScorer = new BehaviouralScorer();
        ChannelScorer channelScorer = new ChannelScorer();
        RiskScoringEngine riskScoringEngine = new RiskScoringEngine(amountScorer, copScorer, behaviouralScorer, channelScorer);
        DecisionEngine decisionEngine = new DecisionEngine();
        ConcurrencyGuard concurrencyGuard = new ConcurrencyGuard();
        CustomerProfileRepository customerProfileRepository = new DynamoDbCustomerProfileRepository(dynamoDbConfig);
        BeneficiaryRegistryRepository beneficiaryRegistryRepository = new DynamoDbBeneficiaryRegistryRepository(dynamoDbConfig);
        EventPublisher eventPublisher = event -> { /* no-op */ };

        handler = new FraudDetectionHandler(
                objectMapper,
                requestValidator,
                riskScoringEngine,
                decisionEngine,
                concurrencyGuard,
                customerProfileRepository,
                beneficiaryRegistryRepository,
                eventPublisher
        );
    }

    @AfterAll
    static void tearDown() {
        if (dynamoDbClient != null) {
            dynamoDbClient.close();
        }
    }

    /**
     * Validates Requirement 4.3: Beneficiary flag updates propagate to subsequent
     * payment evaluations within 5 seconds.
     *
     * Steps:
     * 1. Send a payment with beneficiary flag=NONE → expect non-BLOCK decision
     * 2. Update beneficiary flag to MULE_LINKED in DynamoDB
     * 3. Send the same payment again within 5 seconds → expect BLOCK decision
     */
    @Test
    @Order(1)
    void beneficiaryFlagUpdate_propagatesWithinFiveSeconds() throws Exception {
        // Step 1: Send payment with beneficiary flag=NONE
        FasterPaymentRequest paymentRequest = buildNormalPaymentRequest();
        APIGatewayProxyResponseEvent response1 = invokeHandler(paymentRequest);

        assertThat(response1.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision1 = objectMapper.readValue(response1.getBody(), FraudDecisionResponse.class);

        // With NONE flag and a normal payment, should NOT be BLOCK
        assertThat(decision1.decision()).isNotEqualTo(Decision.BLOCK);

        // Record the time before flag update
        long updateStartTime = System.currentTimeMillis();

        // Step 2: Update beneficiary flag to MULE_LINKED directly in DynamoDB
        updateBeneficiaryFlag("MULE_LINKED");

        // Step 3: Send the same payment again (within 5 seconds)
        APIGatewayProxyResponseEvent response2 = invokeHandler(paymentRequest);

        long elapsed = System.currentTimeMillis() - updateStartTime;

        assertThat(response2.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision2 = objectMapper.readValue(response2.getBody(), FraudDecisionResponse.class);

        // With MULE_LINKED flag, decision MUST be BLOCK (Requirement 4.2)
        assertThat(decision2.decision()).isEqualTo(Decision.BLOCK);

        // Verify the propagation happened within 5 seconds
        assertThat(elapsed).isLessThan(5000L);
    }

    /**
     * Validates that updating beneficiary flag to HIGH_RISK also propagates within 5 seconds,
     * resulting in an elevated risk score (minimum 71) and a BLOCK decision.
     */
    @Test
    @Order(2)
    void beneficiaryFlagUpdateToHighRisk_propagatesWithinFiveSeconds() throws Exception {
        // Reset beneficiary flag to NONE first
        updateBeneficiaryFlag("NONE");

        // Step 1: Send payment with beneficiary flag=NONE
        FasterPaymentRequest paymentRequest = buildNormalPaymentRequest();
        APIGatewayProxyResponseEvent response1 = invokeHandler(paymentRequest);

        assertThat(response1.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision1 = objectMapper.readValue(response1.getBody(), FraudDecisionResponse.class);

        // With NONE flag and a normal payment, should NOT be BLOCK
        assertThat(decision1.decision()).isNotEqualTo(Decision.BLOCK);
        int initialScore = decision1.riskScore();

        // Record the time before flag update
        long updateStartTime = System.currentTimeMillis();

        // Step 2: Update beneficiary flag to HIGH_RISK
        updateBeneficiaryFlag("HIGH_RISK");

        // Step 3: Send the same payment again (within 5 seconds)
        APIGatewayProxyResponseEvent response2 = invokeHandler(paymentRequest);

        long elapsed = System.currentTimeMillis() - updateStartTime;

        assertThat(response2.getStatusCode()).isEqualTo(200);
        FraudDecisionResponse decision2 = objectMapper.readValue(response2.getBody(), FraudDecisionResponse.class);

        // With HIGH_RISK flag, minimum score is 71 → BLOCK decision
        assertThat(decision2.riskScore()).isGreaterThanOrEqualTo(71);
        assertThat(decision2.decision()).isEqualTo(Decision.BLOCK);

        // Verify the propagation happened within 5 seconds
        assertThat(elapsed).isLessThan(5000L);
    }

    // --- Helper methods ---

    private static void insertCustomerProfile() {
        CustomerProfileEntity entity = new CustomerProfileEntity();
        entity.setPk(CustomerProfileEntity.buildPk(DEBTOR_SORT_CODE, DEBTOR_ACCOUNT_NUMBER));
        entity.setSk(CustomerProfileEntity.SK_VALUE);
        entity.setMeanAmount(200.0);
        entity.setStdDevAmount(50.0);
        entity.setTransactionCount90d(30);
        entity.setDevices(List.of("device-abc-123"));
        entity.setLocations(List.of("51.5074,-0.1278"));
        entity.setAvgSessionDuration(300000.0);
        entity.setLastUpdated(Instant.now().toString());

        dynamoDbConfig.customerProfileTable().putItem(entity);
    }

    private static void insertBeneficiaryWithFlag(String flag) {
        BeneficiaryRegistryEntity entity = new BeneficiaryRegistryEntity();
        entity.setPk(BeneficiaryRegistryEntity.buildPk(CREDITOR_SORT_CODE, CREDITOR_ACCOUNT_NUMBER));
        entity.setSk(BeneficiaryRegistryEntity.SK_VALUE);
        entity.setFlag(flag);
        entity.setLastUpdated(Instant.now().toString());
        entity.setReason("Initial setup");

        dynamoDbConfig.beneficiaryRegistryTable().putItem(entity);
    }

    private static void updateBeneficiaryFlag(String newFlag) {
        BeneficiaryRegistryEntity entity = new BeneficiaryRegistryEntity();
        entity.setPk(BeneficiaryRegistryEntity.buildPk(CREDITOR_SORT_CODE, CREDITOR_ACCOUNT_NUMBER));
        entity.setSk(BeneficiaryRegistryEntity.SK_VALUE);
        entity.setFlag(newFlag);
        entity.setLastUpdated(Instant.now().toString());
        entity.setReason("Flag updated for propagation test");

        dynamoDbConfig.beneficiaryRegistryTable().putItem(entity);
    }

    private FasterPaymentRequest buildNormalPaymentRequest() {
        return new FasterPaymentRequest(
                "msg-" + System.nanoTime(),
                new BankAccount(DEBTOR_SORT_CODE, DEBTOR_ACCOUNT_NUMBER, "John Doe"),
                new BankAccount(CREDITOR_SORT_CODE, CREDITOR_ACCOUNT_NUMBER, "Jane Smith"),
                new BigDecimal("150.00"),
                "GBP",
                "Payment ref",
                new ConfirmationOfPayee(CopResult.MATCH, "Jane Smith"),
                new Channel(ChannelType.ONLINE_BANKING, "device-abc-123",
                        new GeoLocation(51.5074, -0.1278), Duration.ofMinutes(5)),
                Instant.now()
        );
    }

    private APIGatewayProxyResponseEvent invokeHandler(FasterPaymentRequest request) throws Exception {
        String body = objectMapper.writeValueAsString(request);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(body);
        return handler.handleRequest(event, null);
    }
}
