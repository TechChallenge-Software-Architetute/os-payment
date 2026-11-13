package com.os.payment.adapter.out.sqs;

import com.os.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentStatusMessage(String order, String correlationId, PaymentStatus action, BigDecimal amount,
                                   String currency, String paymentId, String checkoutUrl,
                                   String errorCode, String errorMessage, Instant occurredAt) {}
