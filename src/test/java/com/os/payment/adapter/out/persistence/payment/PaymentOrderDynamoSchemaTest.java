package com.os.payment.adapter.out.persistence.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

class PaymentOrderDynamoSchemaTest {
    @Test
    void mapsPaymentOrderDocumentToDynamoDbAttributesAndBack() {
        var schema = TableSchema.fromBean(PaymentOrderDocument.class);
        var document = new PaymentOrderDocument();
        document.setOrderId("order-123");
        document.setCustomerEmail("customer@example.com");
        document.setAmount(new BigDecimal("125.50"));
        document.setCurrency("BRL");
        document.setStatus("ORDER_GENERATED");

        var attributes = schema.itemToMap(document, true);
        var restored = schema.mapToItem(attributes);

        assertEquals("order-123", attributes.get("orderId").s());
        assertEquals("customer@example.com", restored.getCustomerEmail());
        assertEquals(0, new BigDecimal("125.50").compareTo(restored.getAmount()));
        assertEquals("BRL", restored.getCurrency());
        assertEquals("ORDER_GENERATED", restored.getStatus());
        assertNull(restored.getCreatedAt());
    }
}
