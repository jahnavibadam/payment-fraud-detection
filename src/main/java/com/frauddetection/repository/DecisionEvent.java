package com.frauddetection.repository;

import com.frauddetection.model.BankAccount;
import com.frauddetection.model.Decision;
import com.frauddetection.model.RiskFactor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DecisionEvent(
    String messageId,
    Instant timestamp,
    BankAccount debtorAccount,
    BankAccount creditorAccount,
    BigDecimal amount,
    int riskScore,
    Decision decision,
    int amountScore,
    int copScore,
    int behaviouralScore,
    int channelScore,
    List<RiskFactor> riskFactors
) {}
