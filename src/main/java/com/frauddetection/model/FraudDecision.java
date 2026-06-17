package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record FraudDecision(
    Decision decision,
    int riskScore,
    List<RiskFactor> topRiskFactors,
    boolean thresholdOverride
) {}
