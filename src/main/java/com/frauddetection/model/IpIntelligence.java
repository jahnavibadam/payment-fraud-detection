package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * IP intelligence enrichment data for a transaction.
 * Contains geolocation, anonymizer detection, reputation, velocity, and novelty signals.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IpIntelligence(
    String ipAddress,
    String country,
    String region,
    boolean isVpn,
    boolean isProxy,
    boolean isTor,
    int ipReputationScore,
    boolean isHighRiskGeo,
    boolean velocityFlag,
    boolean isNewIp,
    Instant lastSeenTimestamp
) {}
