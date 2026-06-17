package com.frauddetection.repository.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB entity for TransactionHistory.
 * PK: CUST#{sortCode}#{accountNumber}
 * SK: TXN#{timestamp}#{messageId}
 */
@DynamoDbBean
public class TransactionHistoryEntity {

    public static final String SK_PREFIX = "TXN#";

    private String pk;
    private String sk;
    private double amount;
    private String creditorSortCode;
    private String creditorAccountNumber;
    private String channel;
    private String decision;

    public TransactionHistoryEntity() {}

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

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getCreditorSortCode() {
        return creditorSortCode;
    }

    public void setCreditorSortCode(String creditorSortCode) {
        this.creditorSortCode = creditorSortCode;
    }

    public String getCreditorAccountNumber() {
        return creditorAccountNumber;
    }

    public void setCreditorAccountNumber(String creditorAccountNumber) {
        this.creditorAccountNumber = creditorAccountNumber;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public static String buildSk(String timestamp, String messageId) {
        return SK_PREFIX + timestamp + "#" + messageId;
    }
}
