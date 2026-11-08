package com.os.payment.adapter.in.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import io.awspring.cloud.sqs.annotation.SqsListener;
import com.os.payment.application.payment.GeneratePaymentUseCase;
import com.os.payment.application.payment.PaymentOrderRequest;

@Component
public class OrderConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);
    public static final String ORDER_ID = "orderId";
    private final GeneratePaymentUseCase useCase;

    public OrderConsumer(GeneratePaymentUseCase useCase) { this.useCase = useCase; }

    @SqsListener("${app.sqs.input-queue:sqs-os-management}")
    public void receive(Message<ManagementOrderMessage> message) {
        ManagementOrderMessage order = message.getPayload();
        if (order.orderId() != null) MDC.put(ORDER_ID, order.orderId());
        else MDC.remove(ORDER_ID);
        try {
            log.info("Received order from SQS input queue");
            useCase.execute(new PaymentOrderRequest(order.orderId(), order.customerEmail(), order.amount(), order.currency()));
            log.info("Finished processing order message");
        } catch (RuntimeException e) {
            log.error("Failed to process order message", e);
            throw e;
        } finally {
            MDC.remove(ORDER_ID);
        }
    }
}
