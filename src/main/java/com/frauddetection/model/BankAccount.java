package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record BankAccount(
    String sortCode,
    String accountNumber,
    String accountName
) {}
