package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RiskBreakdown(
    int amountScore,
    int copScore,
    int behaviouralScore,
    int channelScore,
    int ipScore
) {}
