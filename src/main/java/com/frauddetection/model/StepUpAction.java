package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record StepUpAction(
    StepUpType type,
    String scamTypology,
    List<String> riskFactors,
    int maxAttempts,
    Duration timeout
) {}
