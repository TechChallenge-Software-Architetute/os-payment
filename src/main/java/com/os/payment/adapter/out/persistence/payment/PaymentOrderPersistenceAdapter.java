package com.os.payment.adapter.out.persistence.payment;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import com.os.payment.application.payment.port.out.PaymentOrderPort;
import com.os.payment.domain.PaymentOrder;

@Repository
public class PaymentOrderPersistenceAdapter implements PaymentOrderPort {
    private static final Logger log = LoggerFactory.getLogger(PaymentOrderPersistenceAdapter.class);
    private final DynamoDbTable<PaymentOrderDocument> table;
    private final PaymentOrderMapper mapper;

    public PaymentOrderPersistenceAdapter(DynamoDbTable<PaymentOrderDocument> table, PaymentOrderMapper mapper) {
        this.table = table;
        this.mapper = mapper;
    }

    @Override
    public Optional<PaymentOrder> findByOrderId(String orderId) {
        log.debug("Loading payment order from DynamoDB");
        PaymentOrderDocument document = table.getItem(Key.builder().partitionValue(orderId).build());
        log.debug("Payment order lookup completed; found={}", document != null);
        return Optional.ofNullable(document).map(mapper::toDomain);
    }

    @Override
    public List<PaymentOrder> findPendingOrders(int limit) {
        log.debug("Loading up to {} pending orders from DynamoDB", limit);
        DynamoDbIndex<PaymentOrderDocument> pendingIndex = table.index("status-updatedAt-index");
        return pendingIndex
                .query(request -> request.queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue("ORDER_GENERATED").build())).limit(limit))
                .stream()
                .findFirst()
                .map(page -> page.items().stream().map(mapper::toDomain).toList())
                .orElseGet(List::of);
    }

    @Override
    public PaymentOrder save(PaymentOrder order) {
        log.debug("Saving payment order to DynamoDB; status={}", order.status());
        table.putItem(mapper.toDocument(order));
        log.debug("Payment order saved to DynamoDB");
        return order;
    }

    @Override
    public boolean saveIfPending(PaymentOrder order) {
        var condition = Expression.builder()
                .expression("#status = :pending")
                .putExpressionName("#status", "status")
                .putExpressionValue(":pending", AttributeValue.builder().s("ORDER_GENERATED").build())
                .build();
        try {
            table.updateItem(UpdateItemEnhancedRequest.builder(PaymentOrderDocument.class)
                    .item(mapper.toDocument(order))
                    .ignoreNulls(true)
                    .conditionExpression(condition)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            log.info("Pending payment order was already updated by another worker");
            return false;
        }
    }
}
