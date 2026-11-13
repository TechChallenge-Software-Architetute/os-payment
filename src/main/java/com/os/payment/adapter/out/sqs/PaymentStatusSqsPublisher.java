package com.os.payment.adapter.out.sqs;

import com.os.payment.application.payment.port.out.PaymentStatusPublisherPort;
import com.os.payment.domain.PaymentOrder;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PaymentStatusSqsPublisher implements PaymentStatusPublisherPort {
    private static final Logger log = LoggerFactory.getLogger(PaymentStatusSqsPublisher.class);
    private final SqsTemplate sqs;
    private final String outputQueue;

    public PaymentStatusSqsPublisher(SqsTemplate sqs, @Value("${app.sqs.output-queue:sqs-payment}") String outputQueue) {
        this.sqs = sqs; this.outputQueue = outputQueue;
    }

    @Override
    public void publish(PaymentOrder order) {
        log.debug("Publishing payment status to SQS queue {}", outputQueue);
        sqs.send(to -> to.queue(outputQueue).payload(new PaymentStatusMessage(order.order(), order.correlationId(), order.status(), order.amount(),
                order.currency(), order.providerPaymentId(), order.checkoutUrl(), order.errorCode(), order.errorMessage(), Instant.now())));
        log.info("Payment status sent to SQS queue {}; status={}", outputQueue, order.status());
    }
}
