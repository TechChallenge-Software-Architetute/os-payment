package com.os.payment.adapter.out.sqs;

import java.math.BigDecimal;
import java.time.Instant;
import com.os.payment.domain.PaymentStatus;

public record PaymentStatusMessage(String orderId, PaymentStatus status, BigDecimal amount,
                                   String currency, String paymentId, String checkoutUrl,
                                   String errorCode, String errorMessage, Instant occurredAt) {}
