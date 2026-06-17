package com.frauddetection.scoring;

import com.frauddetection.model.ChannelType;
import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;
import com.frauddetection.model.GeoLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores channel and device risk factors for a payment request.
 * <p>
 * Evaluates:
 * - Unknown device (deviceId not in customer's known devices): +10 points
 * - Geolocation > 50 km from all known locations: +15 points
 * - PHONE channel type: +5 points
 * - No stored device/location history treated as unknown device AND location
 * <p>
 * Total capped at 25.
 */
public class ChannelScorer implements ComponentScorer {

    private static final int UNKNOWN_DEVICE_SCORE = 10;
    private static final int UNKNOWN_LOCATION_SCORE = 15;
    private static final int PHONE_CHANNEL_SCORE = 5;
    private static final int MAX_SCORE = 25;
    private static final double DISTANCE_THRESHOLD_KM = 50.0;
    private static final double EARTH_RADIUS_KM = 6371.0;

    @Override
    public ScorerResult score(FasterPaymentRequest request, CustomerProfile profile) {
        int totalScore = 0;
        List<String> factors = new ArrayList<>();

        // Evaluate unknown device
        if (isUnknownDevice(request, profile)) {
            totalScore += UNKNOWN_DEVICE_SCORE;
            factors.add("unknown device");
        }

        // Evaluate geolocation distance
        if (isUnknownLocation(request, profile)) {
            totalScore += UNKNOWN_LOCATION_SCORE;
            factors.add("location >50km from known locations");
        }

        // Evaluate PHONE channel type
        if (request.channel() != null && request.channel().type() == ChannelType.PHONE) {
            totalScore += PHONE_CHANNEL_SCORE;
            factors.add("PHONE channel");
        }

        // Cap at maximum
        totalScore = Math.min(totalScore, MAX_SCORE);

        String explanation = buildExplanation(totalScore, factors);
        return new ScorerResult(totalScore, explanation);
    }

    private boolean isUnknownDevice(FasterPaymentRequest request, CustomerProfile profile) {
        if (request.channel() == null || request.channel().deviceId() == null) {
            return true;
        }

        List<String> knownDevices = profile.knownDevices();
        if (knownDevices == null || knownDevices.isEmpty()) {
            return true;
        }

        return !knownDevices.contains(request.channel().deviceId());
    }

    private boolean isUnknownLocation(FasterPaymentRequest request, CustomerProfile profile) {
        GeoLocation requestLocation = (request.channel() != null) ? request.channel().geoLocation() : null;

        // If geoLocation is null, do NOT apply the location penalty (can't determine distance)
        if (requestLocation == null) {
            return false;
        }

        // If debtor has no stored location history, treat as unknown location (Requirement 9.5)
        List<GeoLocation> knownLocations = profile.knownLocations();
        if (knownLocations == null || knownLocations.isEmpty()) {
            return true;
        }

        for (GeoLocation known : knownLocations) {
            double distance = haversineDistance(requestLocation, known);
            if (distance <= DISTANCE_THRESHOLD_KM) {
                return false;
            }
        }

        return true;
    }

    /**
     * Calculates the great-circle distance between two geographic coordinates
     * using the Haversine formula.
     *
     * @param loc1 first location
     * @param loc2 second location
     * @return distance in kilometres
     */
    static double haversineDistance(GeoLocation loc1, GeoLocation loc2) {
        double lat1Rad = Math.toRadians(loc1.latitude());
        double lat2Rad = Math.toRadians(loc2.latitude());
        double deltaLat = Math.toRadians(loc2.latitude() - loc1.latitude());
        double deltaLon = Math.toRadians(loc2.longitude() - loc1.longitude());

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }

    private String buildExplanation(int score, List<String> factors) {
        if (factors.isEmpty()) {
            return "Channel risk: no risk factors identified, score=" + score;
        }
        String explanation = "Channel risk (score=" + score + "): " + String.join(", ", factors);
        if (explanation.length() > 200) {
            explanation = explanation.substring(0, 197) + "...";
        }
        return explanation;
    }
}
