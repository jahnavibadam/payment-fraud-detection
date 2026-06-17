package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record FraudDecisionResponse(
    String messageId,
    Decision decision,
    int riskScore,
    RiskBreakdown breakdown,
    List<RiskFactor> riskFactors,
    Instant timestamp
) {}
