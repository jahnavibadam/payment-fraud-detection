package com.frauddetection.scoring;

import com.frauddetection.model.Decision;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.FraudDecisionResponse;
import com.frauddetection.model.RiskAssessment;
import com.frauddetection.model.RiskBreakdown;
import com.frauddetection.model.RiskFactor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Circuit breaker that enforces the 300ms SLA budget for the scoring path.
 * Uses a 280ms timeout for scoring operations, reserving 20ms for response serialization.
 * On timeout or error, returns a REVIEW decision as a safe fallback.
 */
public class TimeoutCircuitBreaker {

    private static final Duration SCORING_TIMEOUT = Duration.ofMillis(280);
    private static final Duration RESPONSE_BUDGET = Duration.ofMillis(20);

    /**
     * Executes the scoring task within the timeout budget.
     *
     * @param scoringTask supplier that performs the risk assessment
     * @param request the original payment request (used for fallback responses)
     * @return a FraudDecisionResponse built from the assessment, or a REVIEW fallback on failure
     */
    public FraudDecisionResponse executeWithTimeout(
            Supplier<RiskAssessment> scoringTask,
            FasterPaymentRequest request) {

        try {
            RiskAssessment assessment = CompletableFuture
                .supplyAsync(scoringTask)
                .get(SCORING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return buildResponse(assessment, request);
        } catch (TimeoutException e) {
            return buildTimeoutReview(request);
        } catch (Exception e) {
            return buildErrorReview(request, e);
        }
    }

    private FraudDecisionResponse buildResponse(RiskAssessment assessment, FasterPaymentRequest request) {
        return new FraudDecisionResponse(
            request.messageId(),
            mapScoreToDecision(assessment.riskScore()),
            assessment.riskScore(),
            new RiskBreakdown(
                assessment.amountScore(),
                assessment.copScore(),
                assessment.behaviouralScore(),
                assessment.channelScore(),
                assessment.ipScore()
            ),
            assessment.riskFactors(),
            Instant.now()
        );
    }

    private FraudDecisionResponse buildTimeoutReview(FasterPaymentRequest request) {
        RiskFactor timeoutFactor = new RiskFactor(
            "timeout",
            "Scoring timeout exceeded 280ms budget; defaulting to REVIEW"
        );
        return new FraudDecisionResponse(
            request.messageId(),
            Decision.REVIEW,
            0,
            new RiskBreakdown(0, 0, 0, 0, 0),
            List.of(timeoutFactor),
            Instant.now()
        );
    }

    private FraudDecisionResponse buildErrorReview(FasterPaymentRequest request, Exception e) {
        RiskFactor errorFactor = new RiskFactor(
            "error",
            "Internal error during scoring: " + truncateMessage(e.getMessage())
        );
        return new FraudDecisionResponse(
            request.messageId(),
            Decision.REVIEW,
            0,
            new RiskBreakdown(0, 0, 0, 0, 0),
            List.of(errorFactor),
            Instant.now()
        );
    }

    private Decision mapScoreToDecision(int riskScore) {
        if (riskScore <= 30) {
            return Decision.ALLOW;
        } else if (riskScore <= 70) {
            return Decision.REVIEW;
        } else {
            return Decision.BLOCK;
        }
    }

    private String truncateMessage(String message) {
        if (message == null) {
            return "unknown";
        }
        // Keep explanation within 200 char limit for RiskFactor
        int maxLength = 160; // leave room for the prefix
        return message.length() > maxLength ? message.substring(0, maxLength) + "..." : message;
    }
}
