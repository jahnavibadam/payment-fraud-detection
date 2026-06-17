package com.frauddetection.model;

/**
 * Detected scam pattern indicator for a payment.
 */
public enum ScamIndicator {
    NONE,
    INVESTMENT_SCAM,
    ROMANCE_SCAM,
    IMPERSONATION,
    INVOICE_REDIRECTION
}
