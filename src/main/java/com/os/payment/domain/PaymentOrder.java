package com.os.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
public record PaymentOrder(
        String order,
        String correlationId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String providerPaymentId,
        String checkoutUrl,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {
    public PaymentOrder withPayment(PaymentStatus newStatus, String paymentId, String checkoutUrl,
                                   String errorCode, String errorMessage, Instant updated) {
        return new PaymentOrder(order, correlationId, amount, currency, newStatus,
                paymentId, checkoutUrl, errorCode, errorMessage, createdAt, updated);
    }
}
