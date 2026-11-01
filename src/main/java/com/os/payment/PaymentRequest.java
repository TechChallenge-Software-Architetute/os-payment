package com.os.payment;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One payment request taken off the queue.
 *
 * <p>The message contract with os-orchestrator is not settled, so this reads the
 * order id from the {@code orderId} message attribute when present and falls back
 * to the raw body. {@code paymentId} is generated here because the request is the
 * first time this payment exists.
 */
public record PaymentRequest(
        String paymentId,
        String orderId,
        String payload,
        String status,
        String receivedAt) {

    /** Initial status. A real implementation would advance this. */
    public static final String STATUS_RECEIVED = "RECEIVED";

    public static PaymentRequest from(SQSEvent.SQSMessage message) {
        String body = message.getBody() == null ? "" : message.getBody();

        String orderId = Optional.ofNullable(message.getMessageAttributes())
                .map(attrs -> attrs.get("orderId"))
                .map(SQSEvent.MessageAttribute::getStringValue)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> body.isBlank() ? "unknown" : body);

        return new PaymentRequest(
                UUID.randomUUID().toString(),
                orderId,
                body,
                STATUS_RECEIVED,
                Instant.now().toString());
    }
}
