package com.os.payment;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumes the payment-request queue this repository owns and records each request
 * in DynamoDB.
 *
 * <p><strong>Placeholder.</strong> The payment business rules and the message
 * contract with os-orchestrator are not defined yet, so this handler persists the
 * request and stops. It deliberately does not publish a result back to the
 * orchestrator: inventing that message shape now would commit the saga to a
 * contract nobody agreed, which the next change would have to undo.
 *
 * <p>Returns a partial batch response so that one bad message does not redrive the
 * whole batch. That requires {@code function_response_types = ["ReportBatchItemFailures"]}
 * on the event source mapping, which the Terraform in {@code infra/terraform} sets.
 */
public class PaymentRequestHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final PaymentRepository repository;

    /** Used by the Lambda runtime. */
    public PaymentRequestHandler() {
        this(PaymentRepository.fromEnv());
    }

    /** Used by tests. */
    public PaymentRequestHandler(PaymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                repository.save(PaymentRequest.from(message));
            } catch (RuntimeException e) {
                // Logged, not rethrown: reporting this one message as failed lets the
                // rest of the batch succeed. SQS redelivers it, and the DLQ catches it
                // after maxReceiveCount.
                JsonLog.warn(context, "payment.save_failed", message.getMessageId(), e.getMessage());
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(message.getMessageId())
                        .build());
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }
}
