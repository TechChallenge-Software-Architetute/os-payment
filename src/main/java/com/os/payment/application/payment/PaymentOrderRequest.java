package com.os.payment.application.payment;

import java.math.BigDecimal;

public record PaymentOrderRequest(String order, BigDecimal amount, String correlationId) {}
