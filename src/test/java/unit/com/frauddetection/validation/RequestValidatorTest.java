package com.frauddetection.validation;

import com.frauddetection.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidatorTest {

    private RequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RequestValidator();
    }

    private FasterPaymentRequest validRequest() {
        return new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"),
                "GBP",
                "Invoice 123",
                new ConfirmationOfPayee(CopResult.MATCH, "Jane Smith"),
                new Channel(ChannelType.MOBILE, "device-123", new GeoLocation(51.5, -0.1), Duration.ofMinutes(5)),
                Instant.now()
        );
    }

    @Test
    void validRequest_shouldPass() {
        ValidationResult result = validator.validate(validRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void nullDebtorAccount_shouldReportMissingRequiredField() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001", null,
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("debtorAccount"));
    }

    @Test
    void nullCreditorAccount_shouldReportMissingRequiredField() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                null,
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("creditorAccount"));
    }

    @Test
    void nullAmount_shouldReportMissingRequiredField() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                null, "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("amount"));
    }

    @Test
    void nullCurrency_shouldReportMissingRequiredField() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), null, "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("currency"));
    }

    @Test
    void nullChannel_shouldReportMissingRequiredField() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, null, Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("channel"));
    }

    @Test
    void invalidDebtorAccountNumber_shouldRejectWith7Digits() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "1234567", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("debtorAccount.accountNumber"));
    }

    @Test
    void invalidDebtorAccountNumber_shouldRejectWithLetters() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "1234ABCD", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("debtorAccount.accountNumber"));
    }

    @Test
    void invalidDebtorSortCode_shouldRejectWith5Digits() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("12345", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("debtorAccount.sortCode"));
    }

    @Test
    void invalidCreditorAccountNumber_shouldReject() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "8765432", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("creditorAccount.accountNumber"));
    }

    @Test
    void invalidCreditorSortCode_shouldReject() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("65432", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("creditorAccount.sortCode"));
    }

    @Test
    void amountBelowMinimum_shouldReject() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("0.001"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("amount"));
    }

    @Test
    void amountAboveMaximum_shouldReject() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("1000000.01"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("amount"));
    }

    @Test
    void amountAtMinimumBoundary_shouldPass() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("0.01"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void amountAtMaximumBoundary_shouldPass() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("1000000.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void invalidCurrency_shouldReject() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "USD", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("currency"));
    }

    @Test
    void paymentReferenceExceeding18Chars_shouldReject() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "This reference is way too long",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("paymentReference"));
    }

    @Test
    void paymentReferenceExactly18Chars_shouldPass() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "123456789012345678",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void nullPaymentReference_shouldPass() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", null,
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void nullChannelType_shouldReject() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(null, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.field().equals("channel.type"));
    }

    @Test
    void multipleErrors_shouldReturnAllErrors() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("12345", "1234567", "John Doe"),  // both invalid
                new BankAccount("65432", "8765432", "Jane Smith"), // both invalid
                new BigDecimal("0.001"), "USD", "This reference is way too long",
                null, new Channel(null, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        // Should have errors for: debtorAccount.accountNumber, debtorAccount.sortCode,
        // creditorAccount.accountNumber, creditorAccount.sortCode, amount, currency,
        // paymentReference, channel.type = 8 errors
        assertThat(result.errors()).hasSize(8);
    }

    @Test
    void nullConfirmationOfPayee_shouldPass() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001",
                new BankAccount("123456", "12345678", "John Doe"),
                new BankAccount("654321", "87654321", "Jane Smith"),
                new BigDecimal("100.00"), "GBP", "ref",
                null, new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validConfirmationOfPayeeWithAllResults_shouldPass() {
        for (CopResult copResult : CopResult.values()) {
            FasterPaymentRequest request = new FasterPaymentRequest(
                    "MSG001",
                    new BankAccount("123456", "12345678", "John Doe"),
                    new BankAccount("654321", "87654321", "Jane Smith"),
                    new BigDecimal("100.00"), "GBP", "ref",
                    new ConfirmationOfPayee(copResult, "Name"),
                    new Channel(ChannelType.MOBILE, "dev1", null, null), Instant.now()
            );

            ValidationResult result = validator.validate(request);
            assertThat(result.valid()).isTrue();
        }
    }

    @Test
    void allChannelTypes_shouldPass() {
        for (ChannelType channelType : ChannelType.values()) {
            FasterPaymentRequest request = new FasterPaymentRequest(
                    "MSG001",
                    new BankAccount("123456", "12345678", "John Doe"),
                    new BankAccount("654321", "87654321", "Jane Smith"),
                    new BigDecimal("100.00"), "GBP", "ref",
                    null, new Channel(channelType, "dev1", null, null), Instant.now()
            );

            ValidationResult result = validator.validate(request);
            assertThat(result.valid()).isTrue();
        }
    }

    @Test
    void allRequiredFieldsMissing_shouldReportAllMissingFields() {
        FasterPaymentRequest request = new FasterPaymentRequest(
                "MSG001", null, null, null, null, null, null, null, Instant.now()
        );

        ValidationResult result = validator.validate(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(5);

        List<String> errorFields = result.errors().stream().map(ValidationError::field).toList();
        assertThat(errorFields).containsExactlyInAnyOrder(
                "debtorAccount", "creditorAccount", "amount", "currency", "channel"
        );
    }
}
