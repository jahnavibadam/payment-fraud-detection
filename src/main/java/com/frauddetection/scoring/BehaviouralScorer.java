package com.frauddetection.scoring;

import com.frauddetection.model.Channel;
import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;

import java.time.Duration;

/**
 * Scores behavioural risk based on session duration relative to the debtor's historical average.
 *
 * <p>When the request's channel session duration is less than 50% of the customer's
 * average session duration, at least 10 points are added. The further below 50% the
 * session is, the higher the score (up to 25).
 *
 * <p>If the customer has no session history (avgSessionDurationMs is 0), the session
 * cannot be compared and the score is 0.
 *
 * <p>If session duration or channel is null, the score is 0.
 *
 * <p>Score range: [0, 25]
 */
public class BehaviouralScorer implements ComponentScorer {

    private static final int MAX_SCORE = 25;
    private static final int MIN_SHORT_SESSION_SCORE = 10;
    private static final double SHORT_SESSION_THRESHOLD = 0.50;

    @Override
    public ScorerResult score(FasterPaymentRequest request, CustomerProfile profile) {
        Channel channel = request.channel();
        if (channel == null) {
            return new ScorerResult(0, "No channel data available for behavioural scoring");
        }

        Duration sessionDuration = channel.sessionDuration();
        if (sessionDuration == null) {
            return new ScorerResult(0, "No session duration available for behavioural scoring");
        }

        double avgSessionMs = profile.avgSessionDurationMs();
        if (avgSessionMs <= 0) {
            return new ScorerResult(0, "No session history available for comparison");
        }

        double sessionMs = sessionDuration.toMillis();
        double ratio = sessionMs / avgSessionMs;

        if (ratio >= SHORT_SESSION_THRESHOLD) {
            return new ScorerResult(0,
                    "Session duration %.0fms is %.0f%% of average %.0fms (above 50%% threshold)"
                            .formatted(sessionMs, ratio * 100, avgSessionMs));
        }

        // Session is below 50% of average — score proportionally
        // At exactly 50%, score would be MIN_SHORT_SESSION_SCORE (10)
        // At 0%, score would be MAX_SCORE (25)
        // Linear interpolation between 10 and 25 based on how far below 50% the session is
        double severityFactor = 1.0 - (ratio / SHORT_SESSION_THRESHOLD);
        int score = MIN_SHORT_SESSION_SCORE + (int) Math.round(severityFactor * (MAX_SCORE - MIN_SHORT_SESSION_SCORE));
        score = Math.min(MAX_SCORE, Math.max(MIN_SHORT_SESSION_SCORE, score));

        return new ScorerResult(score,
                "Session duration %.0fms is %.0f%% of average %.0fms (below 50%% threshold)"
                        .formatted(sessionMs, ratio * 100, avgSessionMs));
    }
}
