package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum Decision {
    ALLOW,
    REVIEW,
    BLOCK
}
