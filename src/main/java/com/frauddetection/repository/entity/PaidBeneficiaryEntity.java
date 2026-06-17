package com.frauddetection.repository.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB entity for PaidBeneficiary.
 * PK: CUST#{sortCode}#{accountNumber}
 * SK: PAID#{creditorSortCode}#{creditorAccountNumber}
 */
@DynamoDbBean
public class PaidBeneficiaryEntity {

    public static final String SK_PREFIX = "PAID#";

    private String pk;
    private String sk;
    private String firstPaidAt; // ISO-8601 timestamp
    private String lastPaidAt;  // ISO-8601 timestamp
    private int transactionCount;

    public PaidBeneficiaryEntity() {}

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

    public String getFirstPaidAt() {
        return firstPaidAt;
    }

    public void setFirstPaidAt(String firstPaidAt) {
        this.firstPaidAt = firstPaidAt;
    }

    public String getLastPaidAt() {
        return lastPaidAt;
    }

    public void setLastPaidAt(String lastPaidAt) {
        this.lastPaidAt = lastPaidAt;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public static String buildSk(String creditorSortCode, String creditorAccountNumber) {
        return SK_PREFIX + creditorSortCode + "#" + creditorAccountNumber;
    }
}
