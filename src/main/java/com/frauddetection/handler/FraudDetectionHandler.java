package com.frauddetection.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.config.JacksonConfig;
import com.frauddetection.model.*;
import com.frauddetection.repository.*;
import com.frauddetection.scoring.*;
import com.frauddetection.validation.RequestValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AWS Lambda handler for the real-time fraud detection scoring path.
 * Orchestrates request validation, risk scoring, decision-making, and event publishing
 * within a 300ms SLA.
 *
 * <p>Flow:
 * 1. Deserialize request
 * 2. Validate request fields
 * 3. Acquire concurrency permit
 * 4. Read customer profile and beneficiary status
 * 5. Score risk (with 280ms timeout)
 * 6. Apply decision logic (beneficiary overrides, threshold overrides)
 * 7. Publish decision event (fire-and-forget)
 * 8. Return response
 */
public class FraudDetectionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionHandler.class);
    private static final long SCORING_TIMEOUT_MS = 280;

    private final ObjectMapper objectMapper;
    private final RequestValidator requestValidator;
    private final RiskScoringEngine riskScoringEngine;
    private final DecisionEngine decisionEngine;
    private final ConcurrencyGuard concurrencyGuard;
    private final CustomerProfileRepository customerProfileRepository;
    private final BeneficiaryRegistryRepository beneficiaryRegistryRepository;
    private final EventPublisher eventPublisher;

    /**
     * Default no-arg constructor used by AWS Lambda runtime.
     * Creates real AWS clients and production dependencies.
     */
    public FraudDetectionHandler() {
        this.objectMapper = JacksonConfig.createObjectMapper();
        this.requestValidator = new RequestValidator();
        this.decisionEngine = new DecisionEngine();
        this.concurrencyGuard = new ConcurrencyGuard();

        // Create scorers and scoring engine
        AmountScorer amountScorer = new AmountScorer();
        CopScorer copScorer = new CopScorer();
        BehaviouralScorer behaviouralScorer = new BehaviouralScorer();
        ChannelScorer channelScorer = new ChannelScorer();
        this.riskScoringEngine = new RiskScoringEngine(amountScorer, copScorer, behaviouralScorer, channelScorer);

        // Create DynamoDB repositories
        DynamoDbClient dynamoDbClient = createDynamoDbClient();
        DynamoDbConfig dynamoDbConfig = new DynamoDbConfig(dynamoDbClient);
        this.customerProfileRepository = new DynamoDbCustomerProfileRepository(dynamoDbConfig);
        this.beneficiaryRegistryRepository = new DynamoDbBeneficiaryRegistryRepository(dynamoDbConfig);

        // Create EventBridge publisher
        EventBridgeClient eventBridgeClient = createEventBridgeClient();
        this.eventPublisher = new EventBridgeEventPublisher(eventBridgeClient);
    }

    /**
     * Injectable constructor for testing.
     */
    public FraudDetectionHandler(ObjectMapper objectMapper,
                                 RequestValidator requestValidator,
                                 RiskScoringEngine riskScoringEngine,
                                 DecisionEngine decisionEngine,
                                 ConcurrencyGuard concurrencyGuard,
                                 CustomerProfileRepository customerProfileRepository,
                                 BeneficiaryRegistryRepository beneficiaryRegistryRepository,
                                 EventPublisher eventPublisher) {
        this.objectMapper = objectMapper;
        this.requestValidator = requestValidator;
        this.riskScoringEngine = riskScoringEngine;
        this.decisionEngine = decisionEngine;
        this.concurrencyGuard = concurrencyGuard;
        this.customerProfileRepository = customerProfileRepository;
        this.beneficiaryRegistryRepository = beneficiaryRegistryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            // 1. Deserialize request
            FasterPaymentRequest request = deserializeRequest(event);
            if (request == null) {
                return buildErrorResponse(400, "Invalid request: unable to parse request body");
            }

            // 2. Validate request
            ValidationResult validationResult = requestValidator.validate(request);
            if (!validationResult.valid()) {
                return buildValidationErrorResponse(validationResult.errors());
            }

            // 3. Acquire concurrency permit
            Optional<FraudDecisionResponse> capacityError = concurrencyGuard.tryAcquire(request);
            if (capacityError.isPresent()) {
                return buildSuccessResponse(capacityError.get());
            }

            try {
                // 4. Read customer profile and beneficiary status
                CustomerProfile profile = loadCustomerProfile(request);
                BeneficiaryStatus beneficiaryStatus = loadBeneficiaryStatus(request);

                // 5. Execute risk scoring with timeout
                RiskAssessment assessment = executeScoring(request, profile, beneficiaryStatus);

                FraudDecisionResponse response;
                if (assessment != null) {
                    // 6. Scoring succeeded - use DecisionEngine for proper decision logic
                    FraudDecision decision = decisionEngine.decide(assessment, beneficiaryStatus, profile, request);

                    response = new FraudDecisionResponse(
                        request.messageId(),
                        decision.decision(),
                        decision.riskScore(),
                        new RiskBreakdown(
                            assessment.amountScore(),
                            assessment.copScore(),
                            assessment.behaviouralScore(),
                            assessment.channelScore(),
                            assessment.ipScore(),
                            assessment.purposeScore()
                        ),
                        decision.topRiskFactors(),
                        Instant.now()
                    );
                } else {
                    // Scoring timed out or errored - build fallback REVIEW response
                    response = buildTimeoutFallbackResponse(request);
                }

                // 7. Publish decision event (fire-and-forget)
                publishDecisionEvent(request, response);

                // 8. Return response
                return buildSuccessResponse(response);

            } finally {
                // Always release the concurrency permit
                concurrencyGuard.release();
            }

        } catch (Exception e) {
            logger.error("Unexpected error processing fraud detection request", e);
            return buildErrorResponse(500, "Internal server error");
        }
    }

    /**
     * Deserializes the API Gateway request body to a FasterPaymentRequest.
     */
    private FasterPaymentRequest deserializeRequest(APIGatewayProxyRequestEvent event) {
        try {
            String body = event.getBody();
            if (body == null || body.isBlank()) {
                return null;
            }
            return objectMapper.readValue(body, FasterPaymentRequest.class);
        } catch (Exception e) {
            logger.error("Failed to deserialize request body", e);
            return null;
        }
    }

    /**
     * Loads the customer profile from DynamoDB, returning a default profile if not found.
     */
    private CustomerProfile loadCustomerProfile(FasterPaymentRequest request) {
        try {
            Optional<CustomerProfile> profile = customerProfileRepository.getProfile(
                request.debtorAccount().sortCode(),
                request.debtorAccount().accountNumber()
            );
            return profile.orElseGet(() -> createDefaultProfile(request));
        } catch (Exception e) {
            logger.warn("Failed to load customer profile, using default", e);
            return createDefaultProfile(request);
        }
    }

    /**
     * Creates a default customer profile with zero history for new/unknown customers.
     */
    private CustomerProfile createDefaultProfile(FasterPaymentRequest request) {
        return new CustomerProfile(
            request.debtorAccount().sortCode(),
            request.debtorAccount().accountNumber(),
            0.0,   // meanAmount
            0.0,   // stdDevAmount
            0,     // transactionCount90d
            Collections.emptyList(), // knownDevices
            Collections.emptyList(), // knownLocations
            0.0,   // avgSessionDurationMs
            null   // lastUpdated
        );
    }

    /**
     * Loads the beneficiary status from DynamoDB.
     * The repository already returns a default NONE status if not found.
     */
    private BeneficiaryStatus loadBeneficiaryStatus(FasterPaymentRequest request) {
        try {
            return beneficiaryRegistryRepository.getStatus(
                request.creditorAccount().sortCode(),
                request.creditorAccount().accountNumber()
            );
        } catch (Exception e) {
            logger.warn("Failed to load beneficiary status, using default NONE", e);
            return new BeneficiaryStatus(BeneficiaryFlag.NONE, Instant.now());
        }
    }

    /**
     * Executes the risk scoring within a 280ms timeout.
     * Returns the RiskAssessment if scoring completes in time, or null on timeout/error.
     */
    private RiskAssessment executeScoring(FasterPaymentRequest request,
                                          CustomerProfile profile,
                                          BeneficiaryStatus beneficiaryStatus) {
        // Extract IP intelligence from the channel if present
        IpIntelligence ipIntelligence = (request.channel() != null)
                ? request.channel().ipIntelligence() : null;

        // Extract purpose analysis from the channel if present
        PurposeAnalysis purposeAnalysis = (request.channel() != null)
                ? request.channel().purposeAnalysis() : null;

        try {
            return CompletableFuture
                .supplyAsync(() -> riskScoringEngine.score(request, profile, beneficiaryStatus, ipIntelligence, purposeAnalysis))
                .get(SCORING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("Risk scoring timed out after {}ms for messageId={}",
                SCORING_TIMEOUT_MS, request.messageId());
            return null;
        } catch (Exception e) {
            logger.error("Risk scoring failed for messageId={}", request.messageId(), e);
            return null;
        }
    }

    /**
     * Builds a REVIEW fallback response when scoring times out or errors.
     */
    private FraudDecisionResponse buildTimeoutFallbackResponse(FasterPaymentRequest request) {
        RiskFactor timeoutFactor = new RiskFactor(
            "timeout",
            "Scoring timeout exceeded 280ms budget; defaulting to REVIEW"
        );
        return new FraudDecisionResponse(
            request.messageId(),
            Decision.REVIEW,
            0,
            new RiskBreakdown(0, 0, 0, 0, 0, 0),
            List.of(timeoutFactor),
            Instant.now()
        );
    }

    /**
     * Publishes a DecisionMade event via EventBridge (fire-and-forget).
     * Errors are logged but never block the response path.
     */
    private void publishDecisionEvent(FasterPaymentRequest request, FraudDecisionResponse response) {
        try {
            DecisionEvent event = new DecisionEvent(
                request.messageId(),
                response.timestamp(),
                request.debtorAccount(),
                request.creditorAccount(),
                request.amount(),
                response.riskScore(),
                response.decision(),
                response.breakdown().amountScore(),
                response.breakdown().copScore(),
                response.breakdown().behaviouralScore(),
                response.breakdown().channelScore(),
                response.riskFactors()
            );
            eventPublisher.publishDecisionMade(event);
        } catch (Exception e) {
            logger.error("Failed to publish decision event for messageId={}", request.messageId(), e);
        }
    }

    /**
     * Builds a validation error response (HTTP 400) with all validation errors.
     */
    private APIGatewayProxyResponseEvent buildValidationErrorResponse(List<ValidationError> errors) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("errors", errors));
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body);
        } catch (Exception e) {
            logger.error("Failed to serialize validation errors", e);
            return buildErrorResponse(400, "Validation failed");
        }
    }

    /**
     * Builds a success response (HTTP 200) with the fraud decision.
     */
    private APIGatewayProxyResponseEvent buildSuccessResponse(FraudDecisionResponse response) {
        try {
            String body = objectMapper.writeValueAsString(response);
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body);
        } catch (Exception e) {
            logger.error("Failed to serialize fraud decision response", e);
            return buildErrorResponse(500, "Failed to serialize response");
        }
    }

    /**
     * Builds a generic error response.
     */
    private APIGatewayProxyResponseEvent buildErrorResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(statusCode)
            .withHeaders(Map.of("Content-Type", "application/json"))
            .withBody("{\"error\":\"" + message + "\"}");
    }

    /**
     * Creates a DynamoDB client, respecting AWS_ENDPOINT_URL for LocalStack compatibility.
     */
    private static DynamoDbClient createDynamoDbClient() {
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            return DynamoDbClient.builder()
                .endpointOverride(java.net.URI.create(endpointUrl))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                        System.getenv().getOrDefault("AWS_ACCESS_KEY_ID", "test"),
                        System.getenv().getOrDefault("AWS_SECRET_ACCESS_KEY", "test"))))
                .build();
        }
        return DynamoDbClient.create();
    }

    /**
     * Creates an EventBridge client, respecting AWS_ENDPOINT_URL for LocalStack compatibility.
     */
    private static EventBridgeClient createEventBridgeClient() {
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            return EventBridgeClient.builder()
                .endpointOverride(java.net.URI.create(endpointUrl))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                        System.getenv().getOrDefault("AWS_ACCESS_KEY_ID", "test"),
                        System.getenv().getOrDefault("AWS_SECRET_ACCESS_KEY", "test"))))
                .build();
        }
        return EventBridgeClient.create();
    }
}
