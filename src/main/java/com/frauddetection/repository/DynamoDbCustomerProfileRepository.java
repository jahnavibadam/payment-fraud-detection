package com.frauddetection.repository;

import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.GeoLocation;
import com.frauddetection.repository.entity.CustomerProfileEntity;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * DynamoDB implementation of CustomerProfileRepository.
 * Uses the DynamoDB Enhanced Client to interact with the single-table design.
 */
public class DynamoDbCustomerProfileRepository implements CustomerProfileRepository {

    private final DynamoDbTable<CustomerProfileEntity> table;

    public DynamoDbCustomerProfileRepository(DynamoDbConfig config) {
        this.table = config.customerProfileTable();
    }

    @Override
    public Optional<CustomerProfile> getProfile(String sortCode, String accountNumber) {
        Key key = Key.builder()
                .partitionValue(CustomerProfileEntity.buildPk(sortCode, accountNumber))
                .sortValue(CustomerProfileEntity.SK_VALUE)
                .build();

        CustomerProfileEntity entity = table.getItem(key);
        if (entity == null) {
            return Optional.empty();
        }

        return Optional.of(toDomainModel(entity, sortCode, accountNumber));
    }

    @Override
    public void updateProfile(CustomerProfile profile) {
        CustomerProfileEntity entity = toEntity(profile);
        table.putItem(entity);
    }

    /**
     * Converts a CustomerProfileEntity to the domain CustomerProfile model.
     */
    private CustomerProfile toDomainModel(CustomerProfileEntity entity, String sortCode, String accountNumber) {
        List<GeoLocation> geoLocations = parseLocations(entity.getLocations());
        List<String> devices = entity.getDevices() != null ? entity.getDevices() : Collections.emptyList();
        Instant lastUpdated = entity.getLastUpdated() != null
                ? Instant.parse(entity.getLastUpdated())
                : null;

        return new CustomerProfile(
                sortCode,
                accountNumber,
                entity.getMeanAmount(),
                entity.getStdDevAmount(),
                entity.getTransactionCount90d(),
                devices,
                geoLocations,
                entity.getAvgSessionDuration(),
                lastUpdated
        );
    }

    /**
     * Converts a domain CustomerProfile to a CustomerProfileEntity for persistence.
     */
    private CustomerProfileEntity toEntity(CustomerProfile profile) {
        CustomerProfileEntity entity = new CustomerProfileEntity();
        entity.setPk(CustomerProfileEntity.buildPk(profile.sortCode(), profile.accountNumber()));
        entity.setSk(CustomerProfileEntity.SK_VALUE);
        entity.setMeanAmount(profile.meanAmount());
        entity.setStdDevAmount(profile.stdDevAmount());
        entity.setTransactionCount90d(profile.transactionCount90d());
        entity.setDevices(profile.knownDevices() != null ? profile.knownDevices() : Collections.emptyList());
        entity.setLocations(serializeLocations(profile.knownLocations()));
        entity.setAvgSessionDuration(profile.avgSessionDurationMs());
        entity.setLastUpdated(profile.lastUpdated() != null ? profile.lastUpdated().toString() : null);
        return entity;
    }

    /**
     * Parses location strings in "latitude,longitude" format back to GeoLocation objects.
     */
    private List<GeoLocation> parseLocations(List<String> locationStrings) {
        if (locationStrings == null || locationStrings.isEmpty()) {
            return Collections.emptyList();
        }

        List<GeoLocation> locations = new ArrayList<>(locationStrings.size());
        for (String loc : locationStrings) {
            String[] parts = loc.split(",");
            if (parts.length == 2) {
                double latitude = Double.parseDouble(parts[0].trim());
                double longitude = Double.parseDouble(parts[1].trim());
                locations.add(new GeoLocation(latitude, longitude));
            }
        }
        return locations;
    }

    /**
     * Serializes GeoLocation objects to "latitude,longitude" format strings.
     */
    private List<String> serializeLocations(List<GeoLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> serialized = new ArrayList<>(locations.size());
        for (GeoLocation loc : locations) {
            serialized.add(loc.latitude() + "," + loc.longitude());
        }
        return serialized;
    }
}
