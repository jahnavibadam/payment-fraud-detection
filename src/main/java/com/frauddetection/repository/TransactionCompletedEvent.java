package com.frauddetection.repository;

import com.frauddetection.model.GeoLocation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event record representing a completed transaction, consumed from EventBridge.
 * Used by the ProfileUpdate Lambda to recalculate customer behavioural statistics.
 */
public record TransactionCompletedEvent(
    String sortCode,
    String accountNumber,
    BigDecimal amount,
    String deviceId,
    GeoLocation geoLocation,
    double sessionDurationMs,
    Instant timestamp
) {}
