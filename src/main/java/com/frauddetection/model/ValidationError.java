package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ValidationError(
    String field,
    String message
) {}
