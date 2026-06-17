package com.frauddetection.scoring;

import com.frauddetection.model.BeneficiaryFlag;
import com.frauddetection.model.BeneficiaryStatus;
import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.Decision;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.FraudDecision;
import com.frauddetection.model.IpIntelligence;
import com.frauddetection.model.RiskAssessment;
import com.frauddetection.model.RiskFactor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps a composite risk score to a fraud decision (ALLOW, REVIEW, or BLOCK),
 * applying beneficiary overrides and dynamic threshold overrides.
 *
 * <p>Decision mapping (Requirements 1.3, 1.4, 1.5):
 * <ul>
 *   <li>[0, 30] → ALLOW</li>
 *   <li>[31, 70] → REVIEW</li>
 *   <li>[71, 100] → BLOCK</li>
 * </ul>
 *
 * <p>Beneficiary overrides (Requirements 4.1, 4.2):
 * <ul>
 *   <li>HIGH_RISK → minimum score 71 (maps to BLOCK)</li>
 *   <li>MULE_LINKED → always BLOCK regardless of score</li>
 * </ul>
 *
 * <p>Dynamic threshold override (Requirement 3.2):
 * If amount exceeds mean + 3σ (or £500 for low-history accounts),
 * the minimum decision is REVIEW.
 */
public class DecisionEngine {

    private static final int ALLOW_UPPER_BOUND = 30;
    private static final int REVIEW_UPPER_BOUND = 70;
    private static final int HIGH_RISK_MINIMUM_SCORE = 71;
    private static final int MIN_TRANSACTIONS_FOR_HISTORY = 5;
    private static final double DEFAULT_THRESHOLD = 500.0;
    private static final int STDDEV_MULTIPLIER = 3;
    private static final int MAX_TOP_RISK_FACTORS = 3;

    /**
     * Produces a fraud decision based on the risk assessment, beneficiary status,
     * customer profile, and payment request.
     *
     * @param assessment        the computed risk assessment with composite score and factors
     * @param beneficiaryStatus the beneficiary's current risk flag
     * @param profile           the debtor's customer profile with historical statistics
     * @param request           the incoming payment request
     * @return a FraudDecision containing the decision, score, top risk factors, and override flag
     */
    public FraudDecision decide(
            RiskAssessment assessment,
            BeneficiaryStatus beneficiaryStatus,
            CustomerProfile profile,
            FasterPaymentRequest request) {

        int riskScore = assessment.riskScore();
        boolean thresholdOverride = false;

        // 1. Apply beneficiary overrides (checked first)
        if (beneficiaryStatus.flag() == BeneficiaryFlag.MULE_LINKED) {
            // MULE_LINKED: always BLOCK regardless of score
            List<RiskFactor> topFactors = selectTopRiskFactors(assessment.riskFactors());
            return new FraudDecision(Decision.BLOCK, riskScore, topFactors, true);
        }

        if (beneficiaryStatus.flag() == BeneficiaryFlag.HIGH_RISK) {
            // HIGH_RISK: minimum score 71
            if (riskScore < HIGH_RISK_MINIMUM_SCORE) {
                riskScore = HIGH_RISK_MINIMUM_SCORE;
            }
            thresholdOverride = true;
        }

        // 2. Apply IP intelligence overrides (TOR → always BLOCK)
        IpIntelligence ipIntelligence = (request.channel() != null)
                ? request.channel().ipIntelligence() : null;
        if (ipIntelligence != null && ipIntelligence.isTor()) {
            List<RiskFactor> topFactors = selectTopRiskFactors(assessment.riskFactors());
            return new FraudDecision(Decision.BLOCK, riskScore, topFactors, true);
        }

        // 3. Apply dynamic threshold override
        if (exceedsDynamicThreshold(request, profile)) {
            // If base score would give ALLOW, override to REVIEW minimum
            Decision baseDecision = mapScoreToDecision(riskScore);
            if (baseDecision == Decision.ALLOW) {
                // Override: minimum decision is REVIEW (score stays as-is, but decision is elevated)
                List<RiskFactor> topFactors = selectTopRiskFactors(assessment.riskFactors());
                return new FraudDecision(Decision.REVIEW, riskScore, topFactors, true);
            }
            // If already REVIEW or BLOCK, the dynamic threshold doesn't change anything
            // but we still mark the override as applied
            thresholdOverride = true;
        }

        // 4. Apply IP intelligence decision elevation (VPN + high value → REVIEW)
        if (ipIntelligence != null && (ipIntelligence.isVpn() || ipIntelligence.isProxy())) {
            if (request.amount().doubleValue() > 1000.0) {
                Decision baseDecision = mapScoreToDecision(riskScore);
                if (baseDecision == Decision.ALLOW) {
                    List<RiskFactor> topFactors = selectTopRiskFactors(assessment.riskFactors());
                    return new FraudDecision(Decision.REVIEW, riskScore, topFactors, true);
                }
            }
        }

        // 5. Map final score to decision
        Decision decision = mapScoreToDecision(riskScore);

        // 6. Select top 3 risk factors
        List<RiskFactor> topFactors = selectTopRiskFactors(assessment.riskFactors());

        return new FraudDecision(decision, riskScore, topFactors, thresholdOverride);
    }

    /**
     * Maps a risk score to a decision based on defined thresholds.
     */
    private Decision mapScoreToDecision(int riskScore) {
        if (riskScore <= ALLOW_UPPER_BOUND) {
            return Decision.ALLOW;
        } else if (riskScore <= REVIEW_UPPER_BOUND) {
            return Decision.REVIEW;
        } else {
            return Decision.BLOCK;
        }
    }

    /**
     * Checks if the payment amount exceeds the debtor's dynamic threshold.
     * For accounts with fewer than 5 transactions in 90 days, uses £500 as default.
     * Otherwise uses mean + 3σ.
     */
    private boolean exceedsDynamicThreshold(FasterPaymentRequest request, CustomerProfile profile) {
        double amount = request.amount().doubleValue();
        double threshold;

        if (profile.transactionCount90d() < MIN_TRANSACTIONS_FOR_HISTORY) {
            threshold = DEFAULT_THRESHOLD;
        } else {
            threshold = profile.meanAmount() + (STDDEV_MULTIPLIER * profile.stdDevAmount());
        }

        return amount > threshold;
    }

    /**
     * Selects up to 3 top risk factors from the assessment's risk factors list.
     * Takes the first 3 entries (assumed to be ordered by score contribution).
     */
    private List<RiskFactor> selectTopRiskFactors(List<RiskFactor> riskFactors) {
        if (riskFactors == null || riskFactors.isEmpty()) {
            return List.of();
        }
        return riskFactors.stream()
                .limit(MAX_TOP_RISK_FACTORS)
                .collect(Collectors.toList());
    }
}
