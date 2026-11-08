package com.os.payment.adapter.in.sqs;

import java.math.BigDecimal;

public record ManagementOrderMessage(String order, BigDecimal amount, String correlationId) {}
