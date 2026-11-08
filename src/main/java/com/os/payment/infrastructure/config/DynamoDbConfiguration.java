package com.os.payment.infrastructure.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.os.payment.adapter.out.persistence.payment.PaymentOrderDocument;

@Configuration
public class DynamoDbConfiguration {
    @Bean
    DynamoDbClient dynamoDbClient(@Value("${AWS_REGION:us-east-1}") String region,
                                  @Value("${DYNAMODB_ENDPOINT:}") String endpoint) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    DynamoDbTable<PaymentOrderDocument> paymentOrdersTable(
            DynamoDbEnhancedClient client,
            @Value("${app.dynamodb.payment-orders-table:payment_orders}") String tableName) {
        return client.table(tableName, TableSchema.fromBean(PaymentOrderDocument.class));
    }
}
