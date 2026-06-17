package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record BeneficiaryStatus(
    BeneficiaryFlag flag,
    Instant lastUpdated
) {}
