package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CustomerProfile(
    String sortCode,
    String accountNumber,
    double meanAmount,
    double stdDevAmount,
    int transactionCount90d,
    List<String> knownDevices,
    List<GeoLocation> knownLocations,
    double avgSessionDurationMs,
    Instant lastUpdated
) {}
