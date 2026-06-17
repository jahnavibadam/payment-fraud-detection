package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RiskAssessment(
    int riskScore,
    int amountScore,
    int copScore,
    int behaviouralScore,
    int channelScore,
    int ipScore,
    int purposeScore,
    List<RiskFactor> riskFactors
) {}
