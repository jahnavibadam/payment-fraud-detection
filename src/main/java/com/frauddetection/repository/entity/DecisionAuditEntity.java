package com.frauddetection.repository.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;

/**
 * DynamoDB entity for DecisionAudit.
 * PK: AUDIT#{messageId}
 * SK: DECISION
 *
 * GSI (DecisionByDate):
 *   GSI PK: DECISION#{decision}
 *   GSI SK: {timestamp}
 */
@DynamoDbBean
public class DecisionAuditEntity {

    public static final String PK_PREFIX = "AUDIT#";
    public static final String SK_VALUE = "DECISION";
    public static final String GSI_NAME = "DecisionByDate";
    public static final String GSI_PK_PREFIX = "DECISION#";

    private String pk;
    private String sk;
    private String timestamp; // ISO-8601 timestamp
    private String debtorAccount; // sortCode#accountNumber
    private String creditorAccount; // sortCode#accountNumber
    private double amount;
    private int riskScore;
    private String decision; // ALLOW, REVIEW, BLOCK
    private int amountScore;
    private int copScore;
    private int behaviouralScore;
    private int channelScore;
    private List<String> riskFactors; // JSON-serialized risk factors
    private List<String> explanations;

    // GSI attributes
    private String gsiPk; // DECISION#{decision}
    private String gsiSk; // {timestamp}

    public DecisionAuditEntity() {}

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

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getDebtorAccount() {
        return debtorAccount;
    }

    public void setDebtorAccount(String debtorAccount) {
        this.debtorAccount = debtorAccount;
    }

    public String getCreditorAccount() {
        return creditorAccount;
    }

    public void setCreditorAccount(String creditorAccount) {
        this.creditorAccount = creditorAccount;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(int riskScore) {
        this.riskScore = riskScore;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public int getAmountScore() {
        return amountScore;
    }

    public void setAmountScore(int amountScore) {
        this.amountScore = amountScore;
    }

    public int getCopScore() {
        return copScore;
    }

    public void setCopScore(int copScore) {
        this.copScore = copScore;
    }

    public int getBehaviouralScore() {
        return behaviouralScore;
    }

    public void setBehaviouralScore(int behaviouralScore) {
        this.behaviouralScore = behaviouralScore;
    }

    public int getChannelScore() {
        return channelScore;
    }

    public void setChannelScore(int channelScore) {
        this.channelScore = channelScore;
    }

    public List<String> getRiskFactors() {
        return riskFactors;
    }

    public void setRiskFactors(List<String> riskFactors) {
        this.riskFactors = riskFactors;
    }

    public List<String> getExplanations() {
        return explanations;
    }

    public void setExplanations(List<String> explanations) {
        this.explanations = explanations;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = GSI_NAME)
    public String getGsiPk() {
        return gsiPk;
    }

    public void setGsiPk(String gsiPk) {
        this.gsiPk = gsiPk;
    }

    @DynamoDbSecondarySortKey(indexNames = GSI_NAME)
    public String getGsiSk() {
        return gsiSk;
    }

    public void setGsiSk(String gsiSk) {
        this.gsiSk = gsiSk;
    }

    public static String buildPk(String messageId) {
        return PK_PREFIX + messageId;
    }

    public static String buildGsiPk(String decision) {
        return GSI_PK_PREFIX + decision;
    }
}
