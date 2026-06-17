package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum CopResult {
    MATCH,
    CLOSE_MATCH,
    NO_MATCH,
    NOT_AVAILABLE
}
