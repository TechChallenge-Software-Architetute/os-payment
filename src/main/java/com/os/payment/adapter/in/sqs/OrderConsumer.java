package com.os.payment.adapter.in.sqs;

import com.os.payment.application.payment.GeneratePaymentUseCase;
import com.os.payment.application.payment.PaymentOrderRequest;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class OrderConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);
    public static final String ORDER_ID = "order";
    public static final String CORRELATION_ID = "correlationId";
    private final GeneratePaymentUseCase useCase;

    public OrderConsumer(GeneratePaymentUseCase useCase) { this.useCase = useCase; }

    @SqsListener("${app.sqs.input-queue:sqs-os-management}")
    public void receive(@Payload String order,
                        @Header("action") String action,
                        @Header("amount") String amount,
                        @Header(name = "correlationId", required = false) String correlationId) {
        if (!"GERAR_ORCAMENTO".equals(action) && !"GERAR_PAGAMENTO".equals(action)) {
            throw new IllegalArgumentException("Unsupported orchestrator payment action: " + action);
        }
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("Orchestrator message is missing the required amount attribute");
        }
        BigDecimal orderAmount = new BigDecimal(amount);
        if (orderAmount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        String eventCorrelationId = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString() : correlationId;
        MDC.put(CORRELATION_ID, eventCorrelationId);
        if (order != null) MDC.put(ORDER_ID, order);
        else MDC.remove(ORDER_ID);
        try {
            log.info("Received orchestrator action {} from SQS input queue", action);
            useCase.execute(new PaymentOrderRequest(order, orderAmount, eventCorrelationId));
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
