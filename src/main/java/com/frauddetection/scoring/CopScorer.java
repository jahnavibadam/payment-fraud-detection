package com.frauddetection.scoring;

import com.frauddetection.model.ConfirmationOfPayee;
import com.frauddetection.model.CopResult;
import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;

/**
 * Scores fraud risk based on Confirmation of Payee (CoP) results.
 * 
 * Mapping:
 * - MATCH → 0 (name confirmed, no risk)
 * - CLOSE_MATCH → 10 (minor discrepancy)
 * - NO_MATCH → 22 (significant mismatch, high risk)
 * - NOT_AVAILABLE → 15 (service unavailable, elevated uncertainty)
 * - null/absent → 15 (no CoP data, elevated uncertainty)
 */
public class CopScorer implements ComponentScorer {

    @Override
    public ScorerResult score(FasterPaymentRequest request, CustomerProfile profile) {
        ConfirmationOfPayee cop = request.confirmationOfPayee();

        if (cop == null || cop.result() == null) {
            return new ScorerResult(15, "CoP data absent or null; elevated uncertainty score applied");
        }

        return switch (cop.result()) {
            case MATCH -> new ScorerResult(0, "CoP result MATCH; payee name confirmed, no risk");
            case CLOSE_MATCH -> new ScorerResult(10, "CoP result CLOSE_MATCH; minor name discrepancy detected");
            case NO_MATCH -> new ScorerResult(22, "CoP result NO_MATCH; payee name does not match account holder");
            case NOT_AVAILABLE -> new ScorerResult(15, "CoP result NOT_AVAILABLE; service unavailable, elevated uncertainty");
        };
    }
}
