package com.os.payment.adapter.out.sqs;

import com.os.payment.application.payment.port.out.PaymentStatusPublisherPort;
import com.os.payment.domain.PaymentOrder;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
        String action = switch (order.status()) {
            case ORDER_GENERATED -> "GERAR_ORCAMENTO";
            case EXECUTAR_SERVICO -> "PAGAMENTO_APROVADO";
            case NEGAR_SERVICO -> "NEGAR_SERVICO";
        };
        sqs.send(outputQueue, MessageBuilder.withPayload(order.order())
                .setHeader("action", action)
                .setHeader("amount", order.amount().toPlainString())
                .setHeader("correlationId", order.correlationId())
                .build());
        log.info("Payment status sent to SQS queue {}; status={}", outputQueue, order.status());
    }
}
