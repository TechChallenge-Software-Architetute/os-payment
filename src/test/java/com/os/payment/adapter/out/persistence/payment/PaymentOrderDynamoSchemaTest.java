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
        document.setOrder("order-123");
        document.setAmount(new BigDecimal("125.50"));
        document.setCurrency("BRL");
        document.setAction("ORDER_GENERATED");

        var attributes = schema.itemToMap(document, true);
        var restored = schema.mapToItem(attributes);

        assertEquals("order-123", attributes.get("order").s());
        assertEquals(0, new BigDecimal("125.50").compareTo(restored.getAmount()));
        assertEquals("BRL", restored.getCurrency());
        assertEquals("ORDER_GENERATED", restored.getAction());
        assertNull(restored.getCreatedAt());
    }
}
