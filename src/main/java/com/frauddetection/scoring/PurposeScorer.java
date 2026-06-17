package com.frauddetection.scoring;

import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.PurposeAnalysis;
import com.frauddetection.model.PurposeCategory;
import com.frauddetection.model.ScamIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores purpose-of-payment risk factors for a transaction.
 * Evaluates scam keywords, purpose category risk, known scam patterns,
 * behavioural deviation, and classification confidence.
 *
 * <p>Raw scoring signals (can exceed 25 in combination):
 * <ul>
 *   <li>Scam keywords in reference: +30</li>
 *   <li>High-risk category (INVESTMENT/UNKNOWN): +25</li>
 *   <li>Known scam pattern (invoice redirection): +50</li>
 *   <li>Behavioural deviation (first-time pattern): +25</li>
 *   <li>Low confidence classification: +10</li>
 * </ul>
 *
 * <p>Maximum raw total: 140
 * <p>Normalization: normalizedScore = min(25, rawScore * 25 / 140)
 * <p>Score range: [0, 25]
 */
public class PurposeScorer {

    private static final int MAX_SCORE = 25;
    private static final int MAX_RAW_SCORE = 140;

    private static final int SCAM_KEYWORDS_SCORE = 30;
    private static final int HIGH_RISK_CATEGORY_SCORE = 25;
    private static final int KNOWN_SCAM_PATTERN_SCORE = 50;
    private static final int BEHAVIOUR_DEVIATION_SCORE = 25;
    private static final int LOW_CONFIDENCE_SCORE = 10;

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;

    private static final Set<String> SCAM_KEYWORDS = Set.of(
        "crypto", "bitcoin", "urgent", "investment", "guaranteed",
        "returns", "profit", "hmrc", "tax refund", "prize",
        "lottery", "inheritance", "wire transfer", "act now",
        "limited time", "safe account", "holding account"
    );

    private static final Pattern SCAM_KEYWORD_PATTERN = buildScamPattern();

    /**
     * Scores purpose-of-payment risk factors.
     *
     * @param request         the payment request (uses paymentReference)
     * @param profile         the customer profile
     * @param purposeAnalysis the purpose analysis enrichment (may be null)
     * @return ScorerResult with score [0, 25] and explanation
     */
    public ScorerResult score(FasterPaymentRequest request,
                              CustomerProfile profile,
                              PurposeAnalysis purposeAnalysis) {

        int rawScore = 0;
        List<String> factors = new ArrayList<>();

        // Signal 1: Scam keywords in payment reference (always checked, even without purposeAnalysis)
        if (containsScamKeywords(request.paymentReference())) {
            rawScore += SCAM_KEYWORDS_SCORE;
            factors.add("SCAM_KEYWORDS_DETECTED");
        }

        if (purposeAnalysis != null) {
            // Signal 2: High-risk category (INVESTMENT or UNKNOWN)
            if (purposeAnalysis.purposeCategory() == PurposeCategory.INVESTMENT
                    || purposeAnalysis.purposeCategory() == PurposeCategory.UNKNOWN) {
                rawScore += HIGH_RISK_CATEGORY_SCORE;
                factors.add("HIGH_RISK_CATEGORY_" + purposeAnalysis.purposeCategory().name());
            }

            // Signal 3: Known scam pattern
            if (purposeAnalysis.scamIndicator() != null
                    && purposeAnalysis.scamIndicator() != ScamIndicator.NONE) {
                rawScore += KNOWN_SCAM_PATTERN_SCORE;
                factors.add("SCAM_PATTERN_" + purposeAnalysis.scamIndicator().name());
            }

            // Signal 4: Behavioural deviation
            if (purposeAnalysis.historicalDeviation()) {
                rawScore += BEHAVIOUR_DEVIATION_SCORE;
                factors.add("PURPOSE_DEVIATION");
            }

            // Signal 5: Low confidence classification
            if (purposeAnalysis.confidenceScore() < LOW_CONFIDENCE_THRESHOLD
                    && purposeAnalysis.confidenceScore() >= 0) {
                rawScore += LOW_CONFIDENCE_SCORE;
                factors.add("LOW_CONFIDENCE");
            }
        }

        // Normalize raw score [0, 140] to [0, 25]
        int normalizedScore = Math.min(MAX_SCORE, (rawScore * MAX_SCORE) / MAX_RAW_SCORE);

        String explanation = buildExplanation(normalizedScore, rawScore, factors);
        return new ScorerResult(normalizedScore, explanation);
    }

    /**
     * Checks if the payment reference contains known scam keywords.
     */
    private boolean containsScamKeywords(String paymentReference) {
        if (paymentReference == null || paymentReference.isBlank()) {
            return false;
        }
        return SCAM_KEYWORD_PATTERN.matcher(paymentReference.toLowerCase()).find();
    }

    private static Pattern buildScamPattern() {
        String joined = String.join("|", SCAM_KEYWORDS.stream()
                .map(Pattern::quote)
                .toList());
        return Pattern.compile("(" + joined + ")", Pattern.CASE_INSENSITIVE);
    }

    private String buildExplanation(int normalizedScore, int rawScore, List<String> factors) {
        if (factors.isEmpty()) {
            return "Purpose risk: no risk factors identified, score=0";
        }
        String explanation = "Purpose risk (score=" + normalizedScore + ", raw=" + rawScore + "): "
                + String.join(", ", factors);
        if (explanation.length() > 200) {
            explanation = explanation.substring(0, 197) + "...";
        }
        return explanation;
    }
}
