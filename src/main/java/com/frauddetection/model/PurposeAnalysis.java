package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Purpose analysis enrichment data for a transaction.
 * Contains payment purpose classification, scam detection, and behavioural deviation signals.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PurposeAnalysis(
    String declaredPurpose,
    PurposeCategory purposeCategory,
    ScamIndicator scamIndicator,
    double confidenceScore,
    boolean historicalDeviation
) {}
