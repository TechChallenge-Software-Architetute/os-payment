package com.os.payment.application.payment;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.os.payment.application.payment.port.out.PaymentOrderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort.PaymentSearchResult;
import com.os.payment.application.payment.port.out.PaymentStatusPublisherPort;
import com.os.payment.domain.PaymentOrder;
import com.os.payment.domain.PaymentStatus;

@Service
public class PollPendingPaymentsUseCase {
    private static final Logger log = LoggerFactory.getLogger(PollPendingPaymentsUseCase.class);
    private final PaymentOrderPort orders;
    private final PaymentProviderPort provider;
    private final PaymentStatusPublisherPort publisher;
    private final int batchSize;

    public PollPendingPaymentsUseCase(PaymentOrderPort orders, PaymentProviderPort provider,
                                     PaymentStatusPublisherPort publisher,
                                     @Value("${app.payment.polling.batch-size:50}") int batchSize) {
        this.orders = orders;
        this.provider = provider;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    public void pollPendingPayments() {
        List<PaymentOrder> pending = orders.findPendingOrders(batchSize);
        if (pending.isEmpty()) return;

        log.info("Polling Mercado Pago for {} pending payment(s)", pending.size());
        for (PaymentOrder order : pending) {
            try {
                poll(order);
            } catch (RuntimeException e) {
                log.error("Payment polling failed; orderId={}, errorType={}", order.orderId(), e.getClass().getSimpleName());
            }
        }
    }

    private void poll(PaymentOrder order) {
        boolean finalStateObserved = false;
        try {
            List<PaymentSearchResult> results = provider.searchPayments(order.orderId()).stream()
                    .filter(payment -> order.orderId().equals(payment.externalReference()))
                    .toList();

            if (results.isEmpty()) {
                log.debug("Mercado Pago has no payment for order yet; orderId={}", order.orderId());
                return;
            }

            PaymentSearchResult approved = results.stream()
                    .filter(payment -> "approved".equalsIgnoreCase(payment.status()))
                    .findFirst()
                    .orElse(null);

            if (approved != null) {
                boolean amountMatches = order.amount() != null && approved.amount() != null
                        && order.amount().compareTo(approved.amount()) == 0;
                boolean currencyMatches = order.currency() == null || order.currency().equalsIgnoreCase(approved.currency());
                if (amountMatches && currencyMatches) {
                    transition(order, approved, PaymentStatus.ORDER_PAID, null, null);
                } else {
                    log.error("Approved Mercado Pago payment does not match order amount or currency; orderId={}, paymentId={}",
                            order.orderId(), approved.paymentId());
                    transition(order, approved, PaymentStatus.ORDER_PAYMENT_ERROR,
                            "PAYMENT_AMOUNT_OR_CURRENCY_MISMATCH", "Approved payment amount or currency does not match order");
                }
                finalStateObserved = true;
                return;
            }

            boolean stillProcessing = results.stream().anyMatch(payment ->
                    "pending".equalsIgnoreCase(payment.status()) || "in_process".equalsIgnoreCase(payment.status()));
            if (stillProcessing) {
                log.debug("Mercado Pago payment is still pending; orderId={}", order.orderId());
                return;
            }

            PaymentSearchResult latest = results.getFirst();
            if ("rejected".equalsIgnoreCase(latest.status()) || "cancelled".equalsIgnoreCase(latest.status())) {
                transition(order, latest, PaymentStatus.ORDER_PAYMENT_ERROR, "PAYMENT_NOT_APPROVED",
                        "Provider status: " + latest.status());
                finalStateObserved = true;
                return;
            }

            log.debug("Mercado Pago payment has non-final status {}; orderId={}", latest.status(), order.orderId());
        } finally {
            if (!finalStateObserved) {
                PaymentOrder rescheduled = order.withPayment(PaymentStatus.ORDER_GENERATED, order.providerPaymentId(),
                        order.checkoutUrl(), order.errorCode(), order.errorMessage(), Instant.now());
                orders.saveIfPending(rescheduled);
            }
        }
    }

    private void transition(PaymentOrder order, PaymentSearchResult payment, PaymentStatus status,
                            String errorCode, String errorMessage) {
        PaymentOrder updated = order.withPayment(status, payment.paymentId(), order.checkoutUrl(),
                errorCode, errorMessage, Instant.now());
        if (!orders.saveIfPending(updated)) return;

        log.info("Payment status changed from polling; orderId={}, paymentId={}, status={}",
                order.orderId(), payment.paymentId(), status);
        publisher.publish(updated);
    }
}
