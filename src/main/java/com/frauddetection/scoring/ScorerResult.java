package com.frauddetection.scoring;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScorerResult(
    int score,
    String explanation
) {}
