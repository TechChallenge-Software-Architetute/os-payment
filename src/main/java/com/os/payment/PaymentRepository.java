package com.os.payment;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

/** Writes payment records to the table this repository owns. */
public class PaymentRepository {

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public PaymentRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /**
     * Builds a repository from the Lambda environment. Credentials and region come
     * from the default provider chain — the execution role — so nothing is
     * hardcoded and there is no endpoint override to leak into production.
     */
    public static PaymentRepository fromEnv() {
        String table = System.getenv("PAYMENTS_TABLE");
        if (table == null || table.isBlank()) {
            throw new IllegalStateException(
                    "PAYMENTS_TABLE is not set. Terraform injects it; refusing to guess the table name.");
        }
        return new PaymentRepository(DynamoDbClient.create(), table);
    }

    public void save(PaymentRequest request) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "paymentId", AttributeValue.builder().s(request.paymentId()).build(),
                        "orderId", AttributeValue.builder().s(request.orderId()).build(),
                        "payload", AttributeValue.builder().s(request.payload()).build(),
                        "status", AttributeValue.builder().s(request.status()).build(),
                        "receivedAt", AttributeValue.builder().s(request.receivedAt()).build()))
                .build());
    }
}
