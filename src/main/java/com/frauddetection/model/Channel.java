package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record Channel(
    ChannelType type,
    String deviceId,
    GeoLocation geoLocation,
    Duration sessionDuration,
    IpIntelligence ipIntelligence
) {}
