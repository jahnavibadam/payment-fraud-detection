package com.frauddetection.scoring;

import com.frauddetection.model.Decision;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.FraudDecisionResponse;
import com.frauddetection.model.RiskBreakdown;
import com.frauddetection.model.RiskFactor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Guards against excessive concurrency by limiting the number of simultaneous
 * fraud scoring operations. Uses a non-blocking semaphore with 500 permits.
 * Callers must release permits in a finally block after scoring completes.
 */
public class ConcurrencyGuard {

    private final Semaphore permits = new Semaphore(500);

    /**
     * Attempts to acquire a permit for processing the request.
     *
     * @param request the payment request to process
     * @return Optional.empty() if a permit was acquired (caller should proceed with scoring),
     *         or an Optional containing a capacity-exceeded error response if no permits are available
     */
    public Optional<FraudDecisionResponse> tryAcquire(FasterPaymentRequest request) {
        if (!permits.tryAcquire()) {
            return Optional.of(buildCapacityExceededError(request));
        }
        return Optional.empty();
    }

    /**
     * Releases a permit back to the pool. Must be called in a finally block
     * after scoring completes (whether successfully or with an error).
     */
    public void release() {
        permits.release();
    }

    private FraudDecisionResponse buildCapacityExceededError(FasterPaymentRequest request) {
        return new FraudDecisionResponse(
            request.messageId(),
            Decision.REVIEW,
            0,
            new RiskBreakdown(0, 0, 0, 0, 0),
            List.of(new RiskFactor("CAPACITY", "System capacity exceeded; request queued for review")),
            Instant.now()
        );
    }
}
