package com.frauddetection.scoring;

import com.frauddetection.model.BeneficiaryStatus;
import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.IpIntelligence;
import com.frauddetection.model.RiskAssessment;
import com.frauddetection.model.RiskFactor;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes AmountScorer, CopScorer, BehaviouralScorer, ChannelScorer, and IpIntelligenceScorer
 * to produce a composite RiskAssessment.
 *
 * <p>Each component scorer contributes a score in [0, 25]. The composite
 * riskScore is the sum of all five, giving a range of [0, 125].
 *
 * <p>Risk factors are collected from any scorer that returns a non-zero score.
 */
public class RiskScoringEngine {

    private final AmountScorer amountScorer;
    private final CopScorer copScorer;
    private final BehaviouralScorer behaviouralScorer;
    private final ChannelScorer channelScorer;
    private final IpIntelligenceScorer ipIntelligenceScorer;

    public RiskScoringEngine(AmountScorer amountScorer,
                             CopScorer copScorer,
                             BehaviouralScorer behaviouralScorer,
                             ChannelScorer channelScorer) {
        this(amountScorer, copScorer, behaviouralScorer, channelScorer, new IpIntelligenceScorer());
    }

    public RiskScoringEngine(AmountScorer amountScorer,
                             CopScorer copScorer,
                             BehaviouralScorer behaviouralScorer,
                             ChannelScorer channelScorer,
                             IpIntelligenceScorer ipIntelligenceScorer) {
        this.amountScorer = amountScorer;
        this.copScorer = copScorer;
        this.behaviouralScorer = behaviouralScorer;
        this.channelScorer = channelScorer;
        this.ipIntelligenceScorer = ipIntelligenceScorer;
    }

    /**
     * Scores a payment request by invoking each component scorer and aggregating results.
     *
     * @param request            the faster payment request to evaluate
     * @param profile            the debtor's customer profile
     * @param beneficiaryStatus  the beneficiary status (reserved for future use)
     * @return a RiskAssessment containing composite and individual scores plus risk factors
     */
    public RiskAssessment score(FasterPaymentRequest request,
                                CustomerProfile profile,
                                BeneficiaryStatus beneficiaryStatus) {
        return score(request, profile, beneficiaryStatus, null);
    }

    /**
     * Scores a payment request including IP intelligence.
     *
     * @param request            the faster payment request to evaluate
     * @param profile            the debtor's customer profile
     * @param beneficiaryStatus  the beneficiary status
     * @param ipIntelligence     the IP intelligence data (may be null)
     * @return a RiskAssessment containing composite and individual scores plus risk factors
     */
    public RiskAssessment score(FasterPaymentRequest request,
                                CustomerProfile profile,
                                BeneficiaryStatus beneficiaryStatus,
                                IpIntelligence ipIntelligence) {

        ScorerResult amountResult = amountScorer.score(request, profile);
        ScorerResult copResult = copScorer.score(request, profile);
        ScorerResult behaviouralResult = behaviouralScorer.score(request, profile);
        ScorerResult channelResult = channelScorer.score(request, profile);
        ScorerResult ipResult = ipIntelligenceScorer.score(request, profile, ipIntelligence);

        int compositeScore = amountResult.score()
                + copResult.score()
                + behaviouralResult.score()
                + channelResult.score()
                + ipResult.score();

        List<RiskFactor> riskFactors = new ArrayList<>();

        if (amountResult.score() > 0) {
            riskFactors.add(new RiskFactor("amount", amountResult.explanation()));
        }
        if (copResult.score() > 0) {
            riskFactors.add(new RiskFactor("cop", copResult.explanation()));
        }
        if (behaviouralResult.score() > 0) {
            riskFactors.add(new RiskFactor("behavioural", behaviouralResult.explanation()));
        }
        if (channelResult.score() > 0) {
            riskFactors.add(new RiskFactor("channel", channelResult.explanation()));
        }
        if (ipResult.score() > 0) {
            riskFactors.add(new RiskFactor("ip", ipResult.explanation()));
        }

        return new RiskAssessment(
                compositeScore,
                amountResult.score(),
                copResult.score(),
                behaviouralResult.score(),
                channelResult.score(),
                ipResult.score(),
                riskFactors
        );
    }
}
