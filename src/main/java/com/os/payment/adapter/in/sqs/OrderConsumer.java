package com.os.payment.adapter.in.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import java.util.UUID;
import io.awspring.cloud.sqs.annotation.SqsListener;
import com.os.payment.application.payment.GeneratePaymentUseCase;
import com.os.payment.application.payment.PaymentOrderRequest;

@Component
public class OrderConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);
    public static final String ORDER_ID = "order";
    public static final String CORRELATION_ID = "correlationId";
    private final GeneratePaymentUseCase useCase;

    public OrderConsumer(GeneratePaymentUseCase useCase) { this.useCase = useCase; }

    @SqsListener("${app.sqs.input-queue:sqs-os-management}")
    public void receive(Message<ManagementOrderMessage> message) {
        ManagementOrderMessage order = message.getPayload();
        String correlationId = order.correlationId() == null || order.correlationId().isBlank()
                ? UUID.randomUUID().toString() : order.correlationId();
        MDC.put(CORRELATION_ID, correlationId);
        if (order.order() != null) MDC.put(ORDER_ID, order.order());
        else MDC.remove(ORDER_ID);
        try {
            log.info("Received order from SQS input queue");
            useCase.execute(new PaymentOrderRequest(order.order(), order.amount(), correlationId));
            log.info("Finished processing order message");
        } catch (RuntimeException e) {
            log.error("Failed to process order message", e);
            throw e;
        } finally {
            MDC.remove(ORDER_ID);
            MDC.remove(CORRELATION_ID);
        }
    }
}
