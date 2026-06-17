package com.frauddetection.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record FasterPaymentRequest(
    String messageId,
    BankAccount debtorAccount,
    BankAccount creditorAccount,
    BigDecimal amount,
    String currency,
    String paymentReference,
    ConfirmationOfPayee confirmationOfPayee,
    Channel channel,
    Instant timestamp
) {}
