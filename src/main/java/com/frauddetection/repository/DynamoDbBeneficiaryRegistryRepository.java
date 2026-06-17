package com.frauddetection.repository;

import com.frauddetection.model.BeneficiaryFlag;
import com.frauddetection.model.BeneficiaryStatus;
import com.frauddetection.repository.entity.BeneficiaryRegistryEntity;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Instant;

/**
 * DynamoDB implementation of BeneficiaryRegistryRepository.
 * Uses single-table design with PK=BENE#{sortCode}#{accountNumber}, SK=STATUS.
 */
public class DynamoDbBeneficiaryRegistryRepository implements BeneficiaryRegistryRepository {

    private final DynamoDbTable<BeneficiaryRegistryEntity> table;

    public DynamoDbBeneficiaryRegistryRepository(DynamoDbConfig config) {
        this.table = config.beneficiaryRegistryTable();
    }

    @Override
    public BeneficiaryStatus getStatus(String sortCode, String accountNumber) {
        Key key = Key.builder()
                .partitionValue(BeneficiaryRegistryEntity.buildPk(sortCode, accountNumber))
                .sortValue(BeneficiaryRegistryEntity.SK_VALUE)
                .build();

        BeneficiaryRegistryEntity entity = table.getItem(key);

        if (entity == null) {
            return new BeneficiaryStatus(BeneficiaryFlag.NONE, Instant.now());
        }

        BeneficiaryFlag flag = BeneficiaryFlag.valueOf(entity.getFlag());
        Instant lastUpdated = Instant.parse(entity.getLastUpdated());

        return new BeneficiaryStatus(flag, lastUpdated);
    }
}
