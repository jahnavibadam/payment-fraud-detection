package com.frauddetection.scoring;

import com.frauddetection.model.BeneficiaryStatus;
import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.IpIntelligence;
import com.frauddetection.model.PurposeAnalysis;
import com.frauddetection.model.RiskAssessment;
import com.frauddetection.model.RiskFactor;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes AmountScorer, CopScorer, BehaviouralScorer, ChannelScorer, IpIntelligenceScorer,
 * and PurposeScorer to produce a composite RiskAssessment.
 *
 * <p>Each component scorer contributes a score in [0, 25]. The composite
 * riskScore is the sum of all six, giving a range of [0, 150].
 *
 * <p>Risk factors are collected from any scorer that returns a non-zero score.
 */
public class RiskScoringEngine {

    private final AmountScorer amountScorer;
    private final CopScorer copScorer;
    private final BehaviouralScorer behaviouralScorer;
    private final ChannelScorer channelScorer;
    private final IpIntelligenceScorer ipIntelligenceScorer;
    private final PurposeScorer purposeScorer;

    public RiskScoringEngine(AmountScorer amountScorer,
                             CopScorer copScorer,
                             BehaviouralScorer behaviouralScorer,
                             ChannelScorer channelScorer) {
        this(amountScorer, copScorer, behaviouralScorer, channelScorer,
             new IpIntelligenceScorer(), new PurposeScorer());
    }

    public RiskScoringEngine(AmountScorer amountScorer,
                             CopScorer copScorer,
                             BehaviouralScorer behaviouralScorer,
                             ChannelScorer channelScorer,
                             IpIntelligenceScorer ipIntelligenceScorer,
                             PurposeScorer purposeScorer) {
        this.amountScorer = amountScorer;
        this.copScorer = copScorer;
        this.behaviouralScorer = behaviouralScorer;
        this.channelScorer = channelScorer;
        this.ipIntelligenceScorer = ipIntelligenceScorer;
        this.purposeScorer = purposeScorer;
    }

    /**
     * Scores a payment request by invoking each component scorer and aggregating results.
     */
    public RiskAssessment score(FasterPaymentRequest request,
                                CustomerProfile profile,
                                BeneficiaryStatus beneficiaryStatus) {
        return score(request, profile, beneficiaryStatus, null, null);
    }

    /**
     * Scores a payment request including IP intelligence (backward compat).
     */
    public RiskAssessment score(FasterPaymentRequest request,
                                CustomerProfile profile,
                                BeneficiaryStatus beneficiaryStatus,
                                IpIntelligence ipIntelligence) {
        return score(request, profile, beneficiaryStatus, ipIntelligence, null);
    }

    /**
     * Scores a payment request including IP intelligence and purpose analysis.
     *
     * @param request            the faster payment request to evaluate
     * @param profile            the debtor's customer profile
     * @param beneficiaryStatus  the beneficiary status
     * @param ipIntelligence     the IP intelligence data (may be null)
     * @param purposeAnalysis    the purpose analysis data (may be null)
     * @return a RiskAssessment containing composite and individual scores plus risk factors
     */
    public RiskAssessment score(FasterPaymentRequest request,
                                CustomerProfile profile,
                                BeneficiaryStatus beneficiaryStatus,
                                IpIntelligence ipIntelligence,
                                PurposeAnalysis purposeAnalysis) {

        ScorerResult amountResult = amountScorer.score(request, profile);
        ScorerResult copResult = copScorer.score(request, profile);
        ScorerResult behaviouralResult = behaviouralScorer.score(request, profile);
        ScorerResult channelResult = channelScorer.score(request, profile);
        ScorerResult ipResult = ipIntelligenceScorer.score(request, profile, ipIntelligence);
        ScorerResult purposeResult = purposeScorer.score(request, profile, purposeAnalysis);

        int compositeScore = amountResult.score()
                + copResult.score()
                + behaviouralResult.score()
                + channelResult.score()
                + ipResult.score()
                + purposeResult.score();

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
        if (purposeResult.score() > 0) {
            riskFactors.add(new RiskFactor("purpose", purposeResult.explanation()));
        }

        return new RiskAssessment(
                compositeScore,
                amountResult.score(),
                copResult.score(),
                behaviouralResult.score(),
                channelResult.score(),
                ipResult.score(),
                purposeResult.score(),
                riskFactors
        );
    }
}
