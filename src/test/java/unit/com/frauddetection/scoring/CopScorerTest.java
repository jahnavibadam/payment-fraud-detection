package com.frauddetection.scoring;

import com.frauddetection.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopScorerTest {

    private CopScorer scorer;
    private CustomerProfile profile;

    @BeforeEach
    void setUp() {
        scorer = new CopScorer();
        profile = new CustomerProfile(
            "123456", "12345678",
            500.0, 100.0, 10,
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1)),
            60000.0, Instant.now()
        );
    }

    @Test
    void matchReturnsZeroScore() {
        FasterPaymentRequest request = buildRequest(new ConfirmationOfPayee(CopResult.MATCH, "John Smith"));
        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isEqualTo(0);
        assertThat(result.explanation()).containsIgnoringCase("MATCH");
    }

    @Test
    void closeMatchReturnsScoreInRange5To15() {
        FasterPaymentRequest request = buildRequest(new ConfirmationOfPayee(CopResult.CLOSE_MATCH, "Jon Smith"));
        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isBetween(5, 15);
        assertThat(result.explanation()).containsIgnoringCase("CLOSE_MATCH");
    }

    @Test
    void noMatchReturnsScoreInRange20To25() {
        FasterPaymentRequest request = buildRequest(new ConfirmationOfPayee(CopResult.NO_MATCH, "Jane Doe"));
        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isBetween(20, 25);
        assertThat(result.explanation()).containsIgnoringCase("NO_MATCH");
    }

    @Test
    void notAvailableReturnsScoreInRange10To20() {
        FasterPaymentRequest request = buildRequest(new ConfirmationOfPayee(CopResult.NOT_AVAILABLE, null));
        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isBetween(10, 20);
        assertThat(result.explanation()).containsIgnoringCase("NOT_AVAILABLE");
    }

    @Test
    void nullConfirmationOfPayeeReturns15() {
        FasterPaymentRequest request = buildRequest(null);
        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isEqualTo(15);
        assertThat(result.explanation()).containsIgnoringCase("absent");
    }

    @Test
    void nullCopResultReturns15() {
        FasterPaymentRequest request = buildRequest(new ConfirmationOfPayee(null, "John Smith"));
        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isEqualTo(15);
        assertThat(result.explanation()).containsIgnoringCase("null");
    }

    @Test
    void allScoresAreWithinComponentBounds() {
        for (CopResult copResult : CopResult.values()) {
            FasterPaymentRequest request = buildRequest(new ConfirmationOfPayee(copResult, "Test"));
            ScorerResult result = scorer.score(request, profile);
            assertThat(result.score()).isBetween(0, 25);
        }

        // null CoP
        ScorerResult nullResult = scorer.score(buildRequest(null), profile);
        assertThat(nullResult.score()).isBetween(0, 25);
    }

    @Test
    void explanationsAreWithin200Characters() {
        for (CopResult copResult : CopResult.values()) {
            FasterPaymentRequest request = buildRequest(new ConfirmationOfPayee(copResult, "Test"));
            ScorerResult result = scorer.score(request, profile);
            assertThat(result.explanation().length()).isLessThanOrEqualTo(200);
        }

        ScorerResult nullResult = scorer.score(buildRequest(null), profile);
        assertThat(nullResult.explanation().length()).isLessThanOrEqualTo(200);
    }

    private FasterPaymentRequest buildRequest(ConfirmationOfPayee cop) {
        return new FasterPaymentRequest(
            "msg-001",
            new BankAccount("123456", "12345678", "Debtor"),
            new BankAccount("654321", "87654321", "Creditor"),
            BigDecimal.valueOf(100.00),
            "GBP",
            "Payment ref",
            cop,
            new Channel(ChannelType.MOBILE, "device1", new GeoLocation(51.5, -0.1), java.time.Duration.ofMinutes(5)),
            Instant.now()
        );
    }
}
