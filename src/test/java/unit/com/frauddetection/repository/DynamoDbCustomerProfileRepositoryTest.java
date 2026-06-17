package com.frauddetection.repository;

import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.GeoLocation;
import com.frauddetection.repository.entity.CustomerProfileEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoDbCustomerProfileRepositoryTest {

    @Mock
    private DynamoDbConfig config;

    @Mock
    private DynamoDbTable<CustomerProfileEntity> table;

    private DynamoDbCustomerProfileRepository repository;

    @BeforeEach
    void setUp() {
        when(config.customerProfileTable()).thenReturn(table);
        repository = new DynamoDbCustomerProfileRepository(config);
    }

    @Test
    void getProfile_returnsProfile_whenEntityExists() {
        // Arrange
        String sortCode = "123456";
        String accountNumber = "12345678";
        Instant now = Instant.parse("2024-01-15T10:30:00Z");

        CustomerProfileEntity entity = new CustomerProfileEntity();
        entity.setPk(CustomerProfileEntity.buildPk(sortCode, accountNumber));
        entity.setSk(CustomerProfileEntity.SK_VALUE);
        entity.setMeanAmount(500.0);
        entity.setStdDevAmount(100.0);
        entity.setTransactionCount90d(45);
        entity.setDevices(List.of("device-1", "device-2"));
        entity.setLocations(List.of("51.5074,-0.1278", "48.8566,2.3522"));
        entity.setAvgSessionDuration(120000.0);
        entity.setLastUpdated(now.toString());

        when(table.getItem(any(Key.class))).thenReturn(entity);

        // Act
        Optional<CustomerProfile> result = repository.getProfile(sortCode, accountNumber);

        // Assert
        assertThat(result).isPresent();
        CustomerProfile profile = result.get();
        assertThat(profile.sortCode()).isEqualTo(sortCode);
        assertThat(profile.accountNumber()).isEqualTo(accountNumber);
        assertThat(profile.meanAmount()).isEqualTo(500.0);
        assertThat(profile.stdDevAmount()).isEqualTo(100.0);
        assertThat(profile.transactionCount90d()).isEqualTo(45);
        assertThat(profile.knownDevices()).containsExactly("device-1", "device-2");
        assertThat(profile.knownLocations()).hasSize(2);
        assertThat(profile.knownLocations().get(0).latitude()).isEqualTo(51.5074);
        assertThat(profile.knownLocations().get(0).longitude()).isEqualTo(-0.1278);
        assertThat(profile.knownLocations().get(1).latitude()).isEqualTo(48.8566);
        assertThat(profile.knownLocations().get(1).longitude()).isEqualTo(2.3522);
        assertThat(profile.avgSessionDurationMs()).isEqualTo(120000.0);
        assertThat(profile.lastUpdated()).isEqualTo(now);
    }

    @Test
    void getProfile_returnsEmpty_whenEntityNotFound() {
        // Arrange
        when(table.getItem(any(Key.class))).thenReturn(null);

        // Act
        Optional<CustomerProfile> result = repository.getProfile("999999", "99999999");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getProfile_handlesNullDevicesAndLocations() {
        // Arrange
        CustomerProfileEntity entity = new CustomerProfileEntity();
        entity.setPk(CustomerProfileEntity.buildPk("123456", "12345678"));
        entity.setSk(CustomerProfileEntity.SK_VALUE);
        entity.setMeanAmount(200.0);
        entity.setStdDevAmount(50.0);
        entity.setTransactionCount90d(10);
        entity.setDevices(null);
        entity.setLocations(null);
        entity.setAvgSessionDuration(60000.0);
        entity.setLastUpdated(null);

        when(table.getItem(any(Key.class))).thenReturn(entity);

        // Act
        Optional<CustomerProfile> result = repository.getProfile("123456", "12345678");

        // Assert
        assertThat(result).isPresent();
        CustomerProfile profile = result.get();
        assertThat(profile.knownDevices()).isEmpty();
        assertThat(profile.knownLocations()).isEmpty();
        assertThat(profile.lastUpdated()).isNull();
    }

    @Test
    void updateProfile_convertsAndPutsEntity() {
        // Arrange
        Instant now = Instant.parse("2024-03-01T12:00:00Z");
        CustomerProfile profile = new CustomerProfile(
                "112233",
                "44556677",
                750.0,
                200.0,
                30,
                List.of("mobile-app", "web-browser"),
                List.of(new GeoLocation(51.5074, -0.1278), new GeoLocation(40.7128, -74.0060)),
                90000.0,
                now
        );

        // Act
        repository.updateProfile(profile);

        // Assert
        ArgumentCaptor<CustomerProfileEntity> captor = ArgumentCaptor.forClass(CustomerProfileEntity.class);
        verify(table).putItem(captor.capture());

        CustomerProfileEntity saved = captor.getValue();
        assertThat(saved.getPk()).isEqualTo("CUST#112233#44556677");
        assertThat(saved.getSk()).isEqualTo("PROFILE");
        assertThat(saved.getMeanAmount()).isEqualTo(750.0);
        assertThat(saved.getStdDevAmount()).isEqualTo(200.0);
        assertThat(saved.getTransactionCount90d()).isEqualTo(30);
        assertThat(saved.getDevices()).containsExactly("mobile-app", "web-browser");
        assertThat(saved.getLocations()).containsExactly("51.5074,-0.1278", "40.7128,-74.006");
        assertThat(saved.getAvgSessionDuration()).isEqualTo(90000.0);
        assertThat(saved.getLastUpdated()).isEqualTo(now.toString());
    }

    @Test
    void updateProfile_handlesNullLocationsAndDevices() {
        // Arrange
        CustomerProfile profile = new CustomerProfile(
                "112233",
                "44556677",
                100.0,
                25.0,
                5,
                null,
                null,
                30000.0,
                null
        );

        // Act
        repository.updateProfile(profile);

        // Assert
        ArgumentCaptor<CustomerProfileEntity> captor = ArgumentCaptor.forClass(CustomerProfileEntity.class);
        verify(table).putItem(captor.capture());

        CustomerProfileEntity saved = captor.getValue();
        assertThat(saved.getDevices()).isEmpty();
        assertThat(saved.getLocations()).isEmpty();
        assertThat(saved.getLastUpdated()).isNull();
    }

    @Test
    void getProfile_usesCorrectKey() {
        // Arrange
        String sortCode = "654321";
        String accountNumber = "87654321";
        when(table.getItem(any(Key.class))).thenReturn(null);

        // Act
        repository.getProfile(sortCode, accountNumber);

        // Assert
        ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(table).getItem(keyCaptor.capture());

        Key capturedKey = keyCaptor.getValue();
        // The key should use the correct PK/SK format
        assertThat(capturedKey.partitionKeyValue().s()).isEqualTo("CUST#654321#87654321");
        assertThat(capturedKey.sortKeyValue().get().s()).isEqualTo("PROFILE");
    }
}
