package com.frauddetection.validation;

import com.frauddetection.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates all fields of a FasterPaymentRequest per Requirement 7.
 * Performs a full-pass validation, collecting ALL errors before returning.
 */
public class RequestValidator {

    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^\\d{8}$");
    private static final Pattern SORT_CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
    private static final String REQUIRED_CURRENCY = "GBP";
    private static final int MAX_PAYMENT_REFERENCE_LENGTH = 18;

    public ValidationResult validate(FasterPaymentRequest request) {
        List<ValidationError> errors = new ArrayList<>();

        validateRequiredFields(request, errors);
        validateDebtorAccount(request.debtorAccount(), errors);
        validateCreditorAccount(request.creditorAccount(), errors);
        validateAmount(request.amount(), errors);
        validateCurrency(request.currency(), errors);
        validatePaymentReference(request.paymentReference(), errors);
        validateChannel(request.channel(), errors);
        validateConfirmationOfPayee(request.confirmationOfPayee(), errors);

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private void validateRequiredFields(FasterPaymentRequest request, List<ValidationError> errors) {
        if (request.debtorAccount() == null) {
            errors.add(new ValidationError("debtorAccount", "debtorAccount is required"));
        }
        if (request.creditorAccount() == null) {
            errors.add(new ValidationError("creditorAccount", "creditorAccount is required"));
        }
        if (request.amount() == null) {
            errors.add(new ValidationError("amount", "amount is required"));
        }
        if (request.currency() == null) {
            errors.add(new ValidationError("currency", "currency is required"));
        }
        if (request.channel() == null) {
            errors.add(new ValidationError("channel", "channel is required"));
        }
    }

    private void validateDebtorAccount(BankAccount account, List<ValidationError> errors) {
        if (account == null) {
            return; // Already reported as missing required field
        }
        if (account.accountNumber() == null || !ACCOUNT_NUMBER_PATTERN.matcher(account.accountNumber()).matches()) {
            errors.add(new ValidationError("debtorAccount.accountNumber",
                    "debtorAccount accountNumber must be exactly 8 numeric digits"));
        }
        if (account.sortCode() == null || !SORT_CODE_PATTERN.matcher(account.sortCode()).matches()) {
            errors.add(new ValidationError("debtorAccount.sortCode",
                    "debtorAccount sortCode must be exactly 6 numeric digits"));
        }
    }

    private void validateCreditorAccount(BankAccount account, List<ValidationError> errors) {
        if (account == null) {
            return; // Already reported as missing required field
        }
        if (account.accountNumber() == null || !ACCOUNT_NUMBER_PATTERN.matcher(account.accountNumber()).matches()) {
            errors.add(new ValidationError("creditorAccount.accountNumber",
                    "creditorAccount accountNumber must be exactly 8 numeric digits"));
        }
        if (account.sortCode() == null || !SORT_CODE_PATTERN.matcher(account.sortCode()).matches()) {
            errors.add(new ValidationError("creditorAccount.sortCode",
                    "creditorAccount sortCode must be exactly 6 numeric digits"));
        }
    }

    private void validateAmount(BigDecimal amount, List<ValidationError> errors) {
        if (amount == null) {
            return; // Already reported as missing required field
        }
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            errors.add(new ValidationError("amount",
                    "amount must be between 0.01 and 1000000.00"));
        }
    }

    private void validateCurrency(String currency, List<ValidationError> errors) {
        if (currency == null) {
            return; // Already reported as missing required field
        }
        if (!REQUIRED_CURRENCY.equals(currency)) {
            errors.add(new ValidationError("currency",
                    "currency must be GBP"));
        }
    }

    private void validatePaymentReference(String paymentReference, List<ValidationError> errors) {
        if (paymentReference == null) {
            return; // paymentReference is optional
        }
        if (paymentReference.length() > MAX_PAYMENT_REFERENCE_LENGTH) {
            errors.add(new ValidationError("paymentReference",
                    "paymentReference must not exceed 18 characters"));
        }
    }

    private void validateChannel(Channel channel, List<ValidationError> errors) {
        if (channel == null) {
            return; // Already reported as missing required field
        }
        if (channel.type() == null) {
            errors.add(new ValidationError("channel.type",
                    "channel type must be one of MOBILE, ONLINE_BANKING, API, BRANCH, PHONE"));
        }
    }

    private void validateConfirmationOfPayee(ConfirmationOfPayee cop, List<ValidationError> errors) {
        if (cop == null) {
            return; // confirmationOfPayee is optional
        }
        if (cop.result() == null) {
            return; // CoP result is optional within the CoP object
        }
        // If the CoP result is present, it's already validated by the enum deserialization.
        // Since CopResult is an enum, if it was deserialized successfully it must be valid.
        // This validation handles the case where the value is set programmatically
        // with an invalid value - but since it's a Java enum, this can't happen at runtime.
        // The validation here is a defensive check for completeness.
    }
}
