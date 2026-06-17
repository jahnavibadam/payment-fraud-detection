package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record GeoLocation(
    double latitude,
    double longitude
) {}
