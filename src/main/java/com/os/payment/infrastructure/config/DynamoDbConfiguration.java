package com.os.payment.infrastructure.config;

import com.os.payment.adapter.out.persistence.payment.PaymentOrderDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@Configuration
public class DynamoDbConfiguration {
    @Bean
    DynamoDbClient dynamoDbClient(@Value("${spring.cloud.aws.region.static:${AWS_REGION:us-east-1}}") String region,
                                  @Value("${app.dynamodb.endpoint:${DYNAMODB_ENDPOINT:}}") String endpoint,
                                  @Value("${spring.cloud.aws.credentials.access-key:}") String accessKey,
                                  @Value("${spring.cloud.aws.credentials.secret-key:}") String secretKey) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
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
