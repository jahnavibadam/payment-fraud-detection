package com.frauddetection.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.config.JacksonConfig;
import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.GeoLocation;
import com.frauddetection.repository.CustomerProfileRepository;
import com.frauddetection.repository.DynamoDbConfig;
import com.frauddetection.repository.DynamoDbCustomerProfileRepository;
import com.frauddetection.repository.TransactionCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AWS Lambda handler that consumes TransactionCompleted events from EventBridge
 * and recalculates customer behavioural statistics (mean, stddev, device list,
 * session averages).
 * <p>
 * Updates the CustomerProfile in DynamoDB via the CustomerProfileRepository.
 * <p>
 * Environment variables:
 * - FRAUD_DETECTION_TABLE: DynamoDB table name (default: "FraudDetection")
 */
public class ProfileUpdateHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger logger = LoggerFactory.getLogger(ProfileUpdateHandler.class);

    private final ObjectMapper objectMapper;
    private final CustomerProfileRepository customerProfileRepository;

    /**
     * Default constructor used by AWS Lambda runtime.
     * Initializes DynamoDB client and repository from environment configuration.
     */
    public ProfileUpdateHandler() {
        this.objectMapper = JacksonConfig.createObjectMapper();
        DynamoDbClient dynamoDbClient = DynamoDbClient.create();
        DynamoDbConfig config = new DynamoDbConfig(dynamoDbClient);
        this.customerProfileRepository = new DynamoDbCustomerProfileRepository(config);
    }

    /**
     * Constructor for testing, allowing injection of dependencies.
     */
    public ProfileUpdateHandler(ObjectMapper objectMapper, CustomerProfileRepository customerProfileRepository) {
        this.objectMapper = objectMapper;
        this.customerProfileRepository = customerProfileRepository;
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        logger.info("Processing TransactionCompleted event");

        try {
            // Deserialize the event detail into TransactionCompletedEvent
            Map<String, Object> detail = event.getDetail();
            String detailJson = objectMapper.writeValueAsString(detail);
            TransactionCompletedEvent txnEvent = objectMapper.readValue(detailJson, TransactionCompletedEvent.class);

            logger.info("Processing transaction for account: {}/{}", txnEvent.sortCode(), txnEvent.accountNumber());

            // Retrieve the current customer profile
            Optional<CustomerProfile> existingProfile = customerProfileRepository.getProfile(
                    txnEvent.sortCode(), txnEvent.accountNumber());

            // Compute updated profile
            CustomerProfile updatedProfile = recalculateProfile(existingProfile, txnEvent);

            // Persist the updated profile
            customerProfileRepository.updateProfile(updatedProfile);

            logger.info("Successfully updated profile for account: {}/{}",
                    txnEvent.sortCode(), txnEvent.accountNumber());
            return "SUCCESS";

        } catch (Exception e) {
            logger.error("Failed to process TransactionCompleted event", e);
            throw new RuntimeException("Failed to process TransactionCompleted event: " + e.getMessage(), e);
        }
    }

    /**
     * Recalculates the customer profile based on the new transaction.
     * Uses incremental (Welford's-style) formulas for mean and standard deviation.
     */
    CustomerProfile recalculateProfile(Optional<CustomerProfile> existingProfileOpt, TransactionCompletedEvent txnEvent) {
        double txnAmount = txnEvent.amount().doubleValue();

        if (existingProfileOpt.isEmpty()) {
            // First transaction for this customer - create a new profile
            List<String> devices = new ArrayList<>();
            if (txnEvent.deviceId() != null && !txnEvent.deviceId().isBlank()) {
                devices.add(txnEvent.deviceId());
            }

            List<GeoLocation> locations = new ArrayList<>();
            if (txnEvent.geoLocation() != null) {
                locations.add(txnEvent.geoLocation());
            }

            return new CustomerProfile(
                    txnEvent.sortCode(),
                    txnEvent.accountNumber(),
                    txnAmount,        // mean = first amount
                    0.0,              // stddev = 0 for single transaction
                    1,                // transactionCount90d = 1
                    devices,
                    locations,
                    txnEvent.sessionDurationMs(),
                    Instant.now()
            );
        }

        CustomerProfile existing = existingProfileOpt.get();

        // Increment transaction count
        int newCount = existing.transactionCount90d() + 1;

        // Recalculate mean using incremental formula: newMean = oldMean + (x - oldMean) / n
        double oldMean = existing.meanAmount();
        double newMean = oldMean + (txnAmount - oldMean) / newCount;

        // Recalculate stddev using Welford's algorithm
        // We maintain the running variance: newVariance based on incremental update
        // For n >= 2: newStdDev = sqrt(((n-2)/(n-1)) * oldVar + ((x - newMean)^2 / n))
        // Simplified incremental approach using running M2:
        double newStdDev = calculateNewStdDev(existing.stdDevAmount(), oldMean, newMean, txnAmount, newCount);

        // Update known devices
        List<String> updatedDevices = updateDeviceList(existing.knownDevices(), txnEvent.deviceId());

        // Update known locations
        List<GeoLocation> updatedLocations = updateLocationList(existing.knownLocations(), txnEvent.geoLocation());

        // Recalculate average session duration using incremental formula
        double oldAvgSession = existing.avgSessionDurationMs();
        double newAvgSession = oldAvgSession + (txnEvent.sessionDurationMs() - oldAvgSession) / newCount;

        return new CustomerProfile(
                existing.sortCode(),
                existing.accountNumber(),
                newMean,
                newStdDev,
                newCount,
                updatedDevices,
                updatedLocations,
                newAvgSession,
                Instant.now()
        );
    }

    /**
     * Calculates the new standard deviation using Welford's online algorithm.
     * <p>
     * The approach reconstructs the M2 aggregate (sum of squared differences from the mean)
     * from the old standard deviation and count, then updates it with the new value.
     */
    double calculateNewStdDev(double oldStdDev, double oldMean, double newMean, double newValue, int newCount) {
        if (newCount < 2) {
            return 0.0;
        }

        // Reconstruct M2 from old stddev: M2 = oldStdDev^2 * (n-1) where n-1 is oldCount
        int oldCount = newCount - 1;
        double oldM2 = oldStdDev * oldStdDev * oldCount;

        // Welford's update: M2 += (newValue - oldMean) * (newValue - newMean)
        double newM2 = oldM2 + (newValue - oldMean) * (newValue - newMean);

        // Population stddev = sqrt(M2 / n), or sample stddev = sqrt(M2 / (n-1))
        // Using population stddev for behavioural profiling
        double variance = newM2 / newCount;
        return Math.sqrt(variance);
    }

    /**
     * Adds the device to the known devices list if not already present.
     */
    List<String> updateDeviceList(List<String> currentDevices, String newDeviceId) {
        if (newDeviceId == null || newDeviceId.isBlank()) {
            return currentDevices != null ? currentDevices : Collections.emptyList();
        }

        List<String> devices = currentDevices != null ? new ArrayList<>(currentDevices) : new ArrayList<>();
        if (!devices.contains(newDeviceId)) {
            devices.add(newDeviceId);
        }
        return devices;
    }

    /**
     * Adds the location to the known locations list if not already present.
     * Uses a simple equality check (exact lat/lng match).
     */
    List<GeoLocation> updateLocationList(List<GeoLocation> currentLocations, GeoLocation newLocation) {
        if (newLocation == null) {
            return currentLocations != null ? currentLocations : Collections.emptyList();
        }

        List<GeoLocation> locations = currentLocations != null ? new ArrayList<>(currentLocations) : new ArrayList<>();
        if (!locations.contains(newLocation)) {
            locations.add(newLocation);
        }
        return locations;
    }
}
