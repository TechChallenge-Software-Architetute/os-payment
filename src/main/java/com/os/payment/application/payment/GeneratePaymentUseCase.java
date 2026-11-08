package com.os.payment.application.payment;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.os.payment.application.payment.port.out.PaymentOrderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort;
import com.os.payment.application.payment.port.out.PaymentStatusPublisherPort;
import com.os.payment.domain.PaymentOrder;
import com.os.payment.domain.PaymentStatus;

@Service
public class GeneratePaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(GeneratePaymentUseCase.class);
    private final PaymentOrderPort orders;
    private final PaymentProviderPort provider;
    private final PaymentStatusPublisherPort publisher;

    public GeneratePaymentUseCase(PaymentOrderPort orders, PaymentProviderPort provider, PaymentStatusPublisherPort publisher) {
        this.orders = orders; this.provider = provider; this.publisher = publisher;
    }

    public void execute(PaymentOrderRequest request) {
        if (request.orderId() == null || request.orderId().isBlank()) {
            log.warn("Ignoring payment request without orderId");
            throw new IllegalArgumentException("orderId is required");
        }
        log.info("Starting payment generation");
        var previous = orders.findByOrderId(request.orderId());
        if (previous.isPresent() && previous.get().status() != PaymentStatus.ORDER_PAYMENT_ERROR) {
            log.info("Skipping duplicate payment request; existing status={}", previous.get().status());
            return;
        }
        Instant now = Instant.now();
        PaymentOrder order = new PaymentOrder(request.orderId(), request.customerEmail(), request.amount(), request.currency(),
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
            order = order.withPayment(PaymentStatus.ORDER_PAYMENT_ERROR, null, null, "PAYMENT_PROVIDER_ERROR", message, Instant.now());
        }
        orders.save(order);
        log.info("Payment order persisted with status={}", order.status());
        publisher.publish(order);
        log.info("Payment status published with status={}", order.status());
    }
}
