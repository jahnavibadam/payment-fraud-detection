package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum BeneficiaryFlag {
    NONE,
    HIGH_RISK,
    MULE_LINKED
}
