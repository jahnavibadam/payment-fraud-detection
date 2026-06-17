package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum StepUpType {
    WARNING,
    SCA_CHALLENGE
}
