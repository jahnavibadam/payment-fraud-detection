package com.frauddetection.repository;

import com.frauddetection.model.CustomerProfile;
import java.util.Optional;

public interface CustomerProfileRepository {
    Optional<CustomerProfile> getProfile(String sortCode, String accountNumber);
    void updateProfile(CustomerProfile profile);
}
