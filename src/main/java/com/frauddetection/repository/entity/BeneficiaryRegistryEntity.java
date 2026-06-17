package com.frauddetection.repository.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB entity for BeneficiaryRegistry.
 * PK: BENE#{sortCode}#{accountNumber}
 * SK: STATUS
 */
@DynamoDbBean
public class BeneficiaryRegistryEntity {

    public static final String PK_PREFIX = "BENE#";
    public static final String SK_VALUE = "STATUS";

    private String pk;
    private String sk;
    private String flag; // NONE, HIGH_RISK, MULE_LINKED
    private String lastUpdated; // ISO-8601 timestamp
    private String reason;

    public BeneficiaryRegistryEntity() {}

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public static String buildPk(String sortCode, String accountNumber) {
        return PK_PREFIX + sortCode + "#" + accountNumber;
    }
}
