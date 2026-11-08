package com.os.payment.application.payment;

import java.math.BigDecimal;

public record PaymentOrderRequest(String orderId, String customerEmail, BigDecimal amount, String currency) {}
