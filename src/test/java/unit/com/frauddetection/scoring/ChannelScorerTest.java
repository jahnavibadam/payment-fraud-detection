package com.frauddetection.scoring;

import com.frauddetection.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelScorerTest {

    private ChannelScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new ChannelScorer();
    }

    @Test
    void knownDeviceAndNearbyLocationScoresZero() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1))
        );
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "device1", new GeoLocation(51.5, -0.1)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isEqualTo(0);
    }

    @Test
    void unknownDeviceAddsAtLeast10Points() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1))
        );
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "unknown-device", new GeoLocation(51.5, -0.1)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isGreaterThanOrEqualTo(10);
        assertThat(result.explanation()).containsIgnoringCase("unknown device");
    }

    @Test
    void nullDeviceIdTreatedAsUnknownDevice() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1))
        );
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, null, new GeoLocation(51.5, -0.1)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isGreaterThanOrEqualTo(10);
        assertThat(result.explanation()).containsIgnoringCase("unknown device");
    }

    @Test
    void locationOver50kmAddsAtLeast15Points() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1))
        );
        // Edinburgh is ~530km from London
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "device1", new GeoLocation(55.95, -3.19)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isGreaterThanOrEqualTo(15);
        assertThat(result.explanation()).containsIgnoringCase("location");
    }

    @Test
    void nullGeoLocationDoesNotApplyLocationPenalty() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1))
        );
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "device1", null
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isEqualTo(0);
    }

    @Test
    void phoneChannelAddsAtLeast5Points() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1))
        );
        FasterPaymentRequest request = buildRequest(
            ChannelType.PHONE, "device1", new GeoLocation(51.5, -0.1)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isGreaterThanOrEqualTo(5);
        assertThat(result.explanation()).containsIgnoringCase("PHONE");
    }

    @Test
    void noStoredDeviceHistoryTreatedAsUnknownDevice() {
        CustomerProfile profile = buildProfile(
            List.of(), List.of(new GeoLocation(51.5, -0.1))
        );
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "device1", new GeoLocation(51.5, -0.1)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void noStoredLocationHistoryWithGeoLocationTreatedAsUnknownLocation() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of()
        );
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "device1", new GeoLocation(51.5, -0.1)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isGreaterThanOrEqualTo(15);
    }

    @Test
    void noStoredHistoryAtAllAppliesBothDeviceAndLocationIncrease() {
        CustomerProfile profile = buildProfile(List.of(), List.of());
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "device1", new GeoLocation(51.5, -0.1)
        );

        ScorerResult result = scorer.score(request, profile);

        // Both unknown device (+10) and unknown location (+15) = 25
        assertThat(result.score()).isGreaterThanOrEqualTo(25);
    }

    @Test
    void totalScoreCappedAt25() {
        CustomerProfile profile = buildProfile(List.of(), List.of());
        // Unknown device (+10) + unknown location (+15) + PHONE (+5) = 30, capped at 25
        FasterPaymentRequest request = buildRequest(
            ChannelType.PHONE, "unknown-device", new GeoLocation(55.95, -3.19)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isEqualTo(25);
    }

    @Test
    void scoreAlwaysWithinBounds() {
        CustomerProfile profile = buildProfile(List.of(), List.of());
        FasterPaymentRequest request = buildRequest(
            ChannelType.PHONE, null, new GeoLocation(55.95, -3.19)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isBetween(0, 25);
    }

    @Test
    void explanationWithin200Characters() {
        CustomerProfile profile = buildProfile(List.of(), List.of());
        FasterPaymentRequest request = buildRequest(
            ChannelType.PHONE, null, new GeoLocation(55.95, -3.19)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.explanation().length()).isLessThanOrEqualTo(200);
    }

    @Test
    void haversineDistanceCalculatesCorrectly() {
        // London to Paris is approximately 340 km
        GeoLocation london = new GeoLocation(51.5074, -0.1278);
        GeoLocation paris = new GeoLocation(48.8566, 2.3522);

        double distance = ChannelScorer.haversineDistance(london, paris);

        assertThat(distance).isBetween(330.0, 350.0);
    }

    @Test
    void haversineDistanceSamePointIsZero() {
        GeoLocation point = new GeoLocation(51.5, -0.1);

        double distance = ChannelScorer.haversineDistance(point, point);

        assertThat(distance).isEqualTo(0.0);
    }

    @Test
    void locationWithin50kmDoesNotAddPoints() {
        CustomerProfile profile = buildProfile(
            List.of("device1"), List.of(new GeoLocation(51.5, -0.1))
        );
        // A location ~10km away
        FasterPaymentRequest request = buildRequest(
            ChannelType.MOBILE, "device1", new GeoLocation(51.55, -0.05)
        );

        ScorerResult result = scorer.score(request, profile);

        assertThat(result.score()).isEqualTo(0);
    }

    private FasterPaymentRequest buildRequest(ChannelType channelType, String deviceId, GeoLocation geoLocation) {
        return new FasterPaymentRequest(
            "msg-001",
            new BankAccount("123456", "12345678", "Debtor"),
            new BankAccount("654321", "87654321", "Creditor"),
            BigDecimal.valueOf(100.00),
            "GBP",
            "Payment ref",
            new ConfirmationOfPayee(CopResult.MATCH, "John Smith"),
            new Channel(channelType, deviceId, geoLocation, Duration.ofMinutes(5)),
            Instant.now()
        );
    }

    private CustomerProfile buildProfile(List<String> knownDevices, List<GeoLocation> knownLocations) {
        return new CustomerProfile(
            "123456", "12345678",
            500.0, 100.0, 10,
            knownDevices, knownLocations,
            60000.0, Instant.now()
        );
    }
}
