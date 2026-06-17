package com.frauddetection.repository.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;

/**
 * DynamoDB entity for CustomerProfile.
 * PK: CUST#{sortCode}#{accountNumber}
 * SK: PROFILE
 */
@DynamoDbBean
public class CustomerProfileEntity {

    public static final String PK_PREFIX = "CUST#";
    public static final String SK_VALUE = "PROFILE";

    private String pk;
    private String sk;
    private double meanAmount;
    private double stdDevAmount;
    private int transactionCount90d;
    private List<String> devices;
    private List<String> locations; // JSON-serialized GeoLocation list
    private double avgSessionDuration;
    private String lastUpdated; // ISO-8601 timestamp

    public CustomerProfileEntity() {}

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

    public double getMeanAmount() {
        return meanAmount;
    }

    public void setMeanAmount(double meanAmount) {
        this.meanAmount = meanAmount;
    }

    public double getStdDevAmount() {
        return stdDevAmount;
    }

    public void setStdDevAmount(double stdDevAmount) {
        this.stdDevAmount = stdDevAmount;
    }

    public int getTransactionCount90d() {
        return transactionCount90d;
    }

    public void setTransactionCount90d(int transactionCount90d) {
        this.transactionCount90d = transactionCount90d;
    }

    public List<String> getDevices() {
        return devices;
    }

    public void setDevices(List<String> devices) {
        this.devices = devices;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations;
    }

    public double getAvgSessionDuration() {
        return avgSessionDuration;
    }

    public void setAvgSessionDuration(double avgSessionDuration) {
        this.avgSessionDuration = avgSessionDuration;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public static String buildPk(String sortCode, String accountNumber) {
        return PK_PREFIX + sortCode + "#" + accountNumber;
    }
}
