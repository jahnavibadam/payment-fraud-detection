package com.frauddetection.repository;

import com.frauddetection.model.BeneficiaryStatus;

public interface BeneficiaryRegistryRepository {
    BeneficiaryStatus getStatus(String sortCode, String accountNumber);
}
