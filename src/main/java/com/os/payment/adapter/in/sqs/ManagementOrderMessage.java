package com.os.payment.adapter.in.sqs;

import java.math.BigDecimal;

public record ManagementOrderMessage(String orderId, String customerEmail, BigDecimal amount, String currency) {}
