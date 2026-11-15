package com.os.payment.application.payment;

import com.os.payment.application.payment.port.out.PaymentOrderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort;
import com.os.payment.application.payment.port.out.PaymentStatusPublisherPort;
import com.os.payment.domain.PaymentOrder;
import com.os.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class GeneratePaymentUseCase {
    private static final String CURRENCY = "BRL";
    private static final Logger log = LoggerFactory.getLogger(GeneratePaymentUseCase.class);
    private final PaymentOrderPort orders;
    private final PaymentProviderPort provider;
    private final PaymentStatusPublisherPort publisher;

    public GeneratePaymentUseCase(PaymentOrderPort orders, PaymentProviderPort provider, PaymentStatusPublisherPort publisher) {
        this.orders = orders; this.provider = provider; this.publisher = publisher;
    }

    public void execute(PaymentOrderRequest request) {
        if (request.order() == null || request.order().isBlank()) {
            log.warn("Ignoring payment request without order");
            throw new IllegalArgumentException("order is required");
        }
        log.info("Starting payment generation");
        var previous = orders.findByOrder(request.order());
        if (previous.isPresent() && previous.get().status() != PaymentStatus.NEGAR_SERVICO) {
            log.info("Skipping duplicate payment request; existing status={}", previous.get().status());
            return;
        }
        Instant now = Instant.now();
        PaymentOrder order = new PaymentOrder(request.order(), request.correlationId(), request.amount(), CURRENCY,
                PaymentStatus.ORDER_GENERATED, null, null, null, null,
                previous.map(PaymentOrder::createdAt).orElse(now), now);
        orders.save(order);
        log.debug("Initial payment order state saved");
        try {
            log.info("Requesting checkout preference from Mercado Pago");
            var preference = provider.createPreference(request);
            order = order.withPayment(PaymentStatus.ORDER_GENERATED, preference.id(), preference.checkoutUrl(), null, null, Instant.now());
            log.info("Checkout preference created; paymentId={}", preference.id());
        } catch (Exception e) {
            log.error("Unable to create Mercado Pago preference; errorType={}", e.getClass().getSimpleName());
            String message = e.getMessage() == null ? "Payment provider request failed" : e.getMessage().substring(0, Math.min(500, e.getMessage().length()));
            order = order.withPayment(PaymentStatus.NEGAR_SERVICO, null, null, "PAYMENT_PROVIDER_ERROR", message, Instant.now());
        }
        orders.save(order);
        log.info("Payment order persisted with status={}", order.status());
        publisher.publish(order);
        log.info("Payment status published with status={}", order.status());
    }
}
