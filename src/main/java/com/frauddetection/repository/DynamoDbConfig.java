package com.frauddetection.repository;

import com.frauddetection.repository.entity.BeneficiaryRegistryEntity;
import com.frauddetection.repository.entity.CustomerProfileEntity;
import com.frauddetection.repository.entity.DecisionAuditEntity;
import com.frauddetection.repository.entity.PaidBeneficiaryEntity;
import com.frauddetection.repository.entity.TransactionHistoryEntity;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * DynamoDB configuration for the fraud detection single-table design.
 * <p>
 * Provides the DynamoDB Enhanced Client, table schemas for each entity type,
 * and factory methods for creating the table and GSI programmatically
 * (useful for LocalStack setup and integration testing).
 * <p>
 * The table name is configurable via the {@code FRAUD_DETECTION_TABLE} environment variable,
 * defaulting to "FraudDetection" if not set.
 */
public class DynamoDbConfig {

    public static final String TABLE_NAME_ENV_VAR = "FRAUD_DETECTION_TABLE";
    public static final String DEFAULT_TABLE_NAME = "FraudDetection";

    public static final String PARTITION_KEY = "pk";
    public static final String SORT_KEY = "sk";
    public static final String GSI_PARTITION_KEY = "gsiPk";
    public static final String GSI_SORT_KEY = "gsiSk";
    public static final String GSI_NAME = DecisionAuditEntity.GSI_NAME;

    private final DynamoDbClient dynamoDbClient;
    private final DynamoDbEnhancedClient enhancedClient;
    private final String tableName;

    /**
     * Creates a DynamoDbConfig with the given DynamoDbClient.
     * The table name is resolved from the FRAUD_DETECTION_TABLE environment variable,
     * or falls back to "FraudDetection".
     */
    public DynamoDbConfig(DynamoDbClient dynamoDbClient) {
        this(dynamoDbClient, resolveTableName());
    }

    /**
     * Creates a DynamoDbConfig with the given DynamoDbClient and explicit table name.
     */
    public DynamoDbConfig(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
        this.tableName = tableName;
    }

    /**
     * Returns the configured DynamoDB Enhanced Client.
     */
    public DynamoDbEnhancedClient enhancedClient() {
        return enhancedClient;
    }

    /**
     * Returns the underlying DynamoDB client.
     */
    public DynamoDbClient dynamoDbClient() {
        return dynamoDbClient;
    }

    /**
     * Returns the configured table name.
     */
    public String tableName() {
        return tableName;
    }

    // --- Table Schema accessors for each entity ---

    public static TableSchema<CustomerProfileEntity> customerProfileSchema() {
        return TableSchema.fromBean(CustomerProfileEntity.class);
    }

    public static TableSchema<BeneficiaryRegistryEntity> beneficiaryRegistrySchema() {
        return TableSchema.fromBean(BeneficiaryRegistryEntity.class);
    }

    public static TableSchema<TransactionHistoryEntity> transactionHistorySchema() {
        return TableSchema.fromBean(TransactionHistoryEntity.class);
    }

    public static TableSchema<PaidBeneficiaryEntity> paidBeneficiarySchema() {
        return TableSchema.fromBean(PaidBeneficiaryEntity.class);
    }

    public static TableSchema<DecisionAuditEntity> decisionAuditSchema() {
        return TableSchema.fromBean(DecisionAuditEntity.class);
    }

    // --- DynamoDbTable accessors ---

    public DynamoDbTable<CustomerProfileEntity> customerProfileTable() {
        return enhancedClient.table(tableName, customerProfileSchema());
    }

    public DynamoDbTable<BeneficiaryRegistryEntity> beneficiaryRegistryTable() {
        return enhancedClient.table(tableName, beneficiaryRegistrySchema());
    }

    public DynamoDbTable<TransactionHistoryEntity> transactionHistoryTable() {
        return enhancedClient.table(tableName, transactionHistorySchema());
    }

    public DynamoDbTable<PaidBeneficiaryEntity> paidBeneficiaryTable() {
        return enhancedClient.table(tableName, paidBeneficiarySchema());
    }

    public DynamoDbTable<DecisionAuditEntity> decisionAuditTable() {
        return enhancedClient.table(tableName, decisionAuditSchema());
    }

    // --- Table creation factory method ---

    /**
     * Creates the DynamoDB table with the single-table design schema and GSI.
     * Useful for LocalStack setup and integration tests.
     *
     * @return the CreateTableResponse from DynamoDB
     */
    public CreateTableResponse createTable() {
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(tableName)
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName(PARTITION_KEY)
                                .keyType(KeyType.HASH)
                                .build(),
                        KeySchemaElement.builder()
                                .attributeName(SORT_KEY)
                                .keyType(KeyType.RANGE)
                                .build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName(PARTITION_KEY)
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName(SORT_KEY)
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName(GSI_PARTITION_KEY)
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName(GSI_SORT_KEY)
                                .attributeType(ScalarAttributeType.S)
                                .build()
                )
                .globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder()
                                .indexName(GSI_NAME)
                                .keySchema(
                                        KeySchemaElement.builder()
                                                .attributeName(GSI_PARTITION_KEY)
                                                .keyType(KeyType.HASH)
                                                .build(),
                                        KeySchemaElement.builder()
                                                .attributeName(GSI_SORT_KEY)
                                                .keyType(KeyType.RANGE)
                                                .build()
                                )
                                .projection(Projection.builder()
                                        .projectionType(ProjectionType.ALL)
                                        .build())
                                .build()
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

        return dynamoDbClient.createTable(request);
    }

    // --- Helper methods ---

    private static String resolveTableName() {
        String envValue = System.getenv(TABLE_NAME_ENV_VAR);
        return (envValue != null && !envValue.isBlank()) ? envValue : DEFAULT_TABLE_NAME;
    }
}
