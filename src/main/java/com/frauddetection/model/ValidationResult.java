package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ValidationResult(
    boolean valid,
    List<ValidationError> errors
) {}
