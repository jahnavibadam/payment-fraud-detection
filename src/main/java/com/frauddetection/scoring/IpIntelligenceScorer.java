package com.frauddetection.scoring;

import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.IpIntelligence;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores IP-based risk factors for a payment request.
 * Evaluates VPN/proxy, TOR, high-risk geo, reputation, velocity, and new IP signals.
 *
 * <p>Raw scoring signals (can exceed 25 in combination):
 * <ul>
 *   <li>VPN/Proxy: +25</li>
 *   <li>TOR: +40</li>
 *   <li>High-risk geo: +20</li>
 *   <li>Reputation > 70: +30</li>
 *   <li>Velocity anomaly: +20</li>
 *   <li>New/unseen IP: +15</li>
 * </ul>
 *
 * <p>Maximum raw total: 150
 * <p>Normalization: normalizedScore = min(25, rawScore * 25 / 150)
 * <p>Score range: [0, 25]
 */
public class IpIntelligenceScorer {

    private static final int MAX_SCORE = 25;
    private static final int MAX_RAW_SCORE = 150;

    private static final int VPN_PROXY_SCORE = 25;
    private static final int TOR_SCORE = 40;
    private static final int HIGH_RISK_GEO_SCORE = 20;
    private static final int REPUTATION_SCORE = 30;
    private static final int VELOCITY_SCORE = 20;
    private static final int NEW_IP_SCORE = 15;
    private static final int REPUTATION_THRESHOLD = 70;

    /**
     * Scores IP intelligence risk factors.
     *
     * @param request        the payment request
     * @param profile        the customer profile
     * @param ipIntelligence the enriched IP data (may be null)
     * @return ScorerResult with score [0, 25] and explanation
     */
    public ScorerResult score(FasterPaymentRequest request,
                              CustomerProfile profile,
                              IpIntelligence ipIntelligence) {

        if (ipIntelligence == null) {
            return new ScorerResult(0, "IP intelligence unavailable");
        }

        int rawScore = 0;
        List<String> factors = new ArrayList<>();

        // Signal 1: VPN or Proxy detected
        if (ipIntelligence.isVpn() || ipIntelligence.isProxy()) {
            rawScore += VPN_PROXY_SCORE;
            factors.add(ipIntelligence.isVpn() ? "VPN_DETECTED" : "PROXY_DETECTED");
        }

        // Signal 2: TOR usage (additive, not exclusive with VPN)
        if (ipIntelligence.isTor()) {
            rawScore += TOR_SCORE;
            factors.add("TOR_DETECTED");
        }

        // Signal 3: High-risk geography
        if (ipIntelligence.isHighRiskGeo()) {
            rawScore += HIGH_RISK_GEO_SCORE;
            factors.add("HIGH_RISK_GEO");
        }

        // Signal 4: IP reputation above threshold
        if (ipIntelligence.ipReputationScore() > REPUTATION_THRESHOLD) {
            rawScore += REPUTATION_SCORE;
            factors.add("HIGH_REPUTATION_RISK");
        }

        // Signal 5: Velocity anomaly
        if (ipIntelligence.velocityFlag()) {
            rawScore += VELOCITY_SCORE;
            factors.add("VELOCITY_ANOMALY");
        }

        // Signal 6: New/unseen IP for this customer
        if (ipIntelligence.isNewIp()) {
            rawScore += NEW_IP_SCORE;
            factors.add("NEW_IP");
        }

        // Normalize raw score [0, 150] to [0, 25]
        int normalizedScore = Math.min(MAX_SCORE, (rawScore * MAX_SCORE) / MAX_RAW_SCORE);

        String explanation = buildExplanation(normalizedScore, rawScore, factors);
        return new ScorerResult(normalizedScore, explanation);
    }

    private String buildExplanation(int normalizedScore, int rawScore, List<String> factors) {
        if (factors.isEmpty()) {
            return "IP risk: no risk factors identified, score=0";
        }
        String explanation = "IP risk (score=" + normalizedScore + ", raw=" + rawScore + "): "
                + String.join(", ", factors);
        if (explanation.length() > 200) {
            explanation = explanation.substring(0, 197) + "...";
        }
        return explanation;
    }
}
