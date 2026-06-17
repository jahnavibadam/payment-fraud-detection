package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfirmationOfPayee(
    CopResult result,
    String matchedName
) {}
