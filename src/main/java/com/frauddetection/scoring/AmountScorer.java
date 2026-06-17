package com.frauddetection.scoring;

import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;

import java.math.BigDecimal;

/**
 * Scores transaction amount risk based on deviation from the debtor's historical mean.
 *
 * <p>When the debtor has fewer than 5 transactions in the most recent 90 days,
 * a default threshold of £500 is applied (Requirement 3.4).
 *
 * <p>When the amount exceeds mean + 3σ, the score is calculated as:
 * min(25, 10 × floor((amount - mean) / stddev)) (Requirement 3.1).
 */
public class AmountScorer implements ComponentScorer {

    private static final int MAX_SCORE = 25;
    private static final int POINTS_PER_STDDEV = 10;
    private static final int MIN_TRANSACTIONS_FOR_HISTORY = 5;
    private static final double DEFAULT_THRESHOLD = 500.0;
    private static final double DEFAULT_STDDEV = 166.67; // DEFAULT_THRESHOLD / 3 so that 3σ = 500
    private static final int STDDEV_MULTIPLIER = 3;

    @Override
    public ScorerResult score(FasterPaymentRequest request, CustomerProfile profile) {
        double amount = request.amount().doubleValue();

        double mean;
        double stddev;

        if (profile.transactionCount90d() < MIN_TRANSACTIONS_FOR_HISTORY) {
            // Requirement 3.4: Apply default threshold of £500
            mean = DEFAULT_THRESHOLD;
            stddev = DEFAULT_STDDEV;
        } else {
            mean = profile.meanAmount();
            stddev = profile.stdDevAmount();
        }

        // Guard against zero or negative stddev
        if (stddev <= 0) {
            // If stddev is zero, any amount above mean is infinitely deviating
            if (amount > mean) {
                return new ScorerResult(MAX_SCORE,
                        String.format("Amount £%.2f exceeds mean £%.2f with zero variance", amount, mean));
            }
            return new ScorerResult(0,
                    String.format("Amount £%.2f within expected range (mean £%.2f)", amount, mean));
        }

        double threshold = mean + (STDDEV_MULTIPLIER * stddev);

        if (amount <= threshold) {
            return new ScorerResult(0,
                    String.format("Amount £%.2f within %.0fσ of mean £%.2f", amount, (double) STDDEV_MULTIPLIER, mean));
        }

        // Amount exceeds mean + 3σ: score = min(25, 10 × floor((amount - mean) / stddev))
        double deviations = (amount - mean) / stddev;
        int rawScore = POINTS_PER_STDDEV * (int) Math.floor(deviations);
        int score = Math.min(MAX_SCORE, rawScore);

        String explanation;
        if (profile.transactionCount90d() < MIN_TRANSACTIONS_FOR_HISTORY) {
            explanation = String.format(
                    "Amount £%.2f exceeds default threshold £%.2f (%.1fσ above mean, low history)",
                    amount, DEFAULT_THRESHOLD, deviations);
        } else {
            explanation = String.format(
                    "Amount £%.2f is %.1fσ above historical mean £%.2f",
                    amount, deviations, mean);
        }

        return new ScorerResult(score, explanation);
    }
}
