package com.os.payment.application.payment;

import com.os.payment.application.payment.port.out.PaymentOrderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort.PaymentSearchResult;
import com.os.payment.application.payment.port.out.PaymentStatusPublisherPort;
import com.os.payment.domain.PaymentOrder;
import com.os.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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
            MDC.put("correlationId", order.correlationId() == null ? "-" : order.correlationId());
            MDC.put("order", order.order());
            try {
                poll(order);
            } catch (RuntimeException e) {
                log.error("Payment polling failed; order={}, errorType={}", order.order(), e.getClass().getSimpleName());
            } finally {
                MDC.remove("correlationId");
                MDC.remove("order");
            }
        }
    }

    private void poll(PaymentOrder order) {
        boolean finalStateObserved = false;
        try {
            List<PaymentSearchResult> results = provider.searchPayments(order.order()).stream()
                    .filter(payment -> order.order().equals(payment.externalReference()))
                    .toList();

            if (results.isEmpty()) {
                log.debug("Mercado Pago has no payment for order yet; order={}", order.order());
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
                    transition(order, approved, PaymentStatus.EXECUTAR_SERVICO, null, null);
                } else {
                    log.error("Approved Mercado Pago payment does not match order amount or currency; order={}, paymentId={}",
                            order.order(), approved.paymentId());
                    transition(order, approved, PaymentStatus.NEGAR_SERVICO,
                            "PAYMENT_AMOUNT_OR_CURRENCY_MISMATCH", "Approved payment amount or currency does not match order");
                }
                finalStateObserved = true;
                return;
            }

            boolean stillProcessing = results.stream().anyMatch(payment ->
                    "pending".equalsIgnoreCase(payment.status()) || "in_process".equalsIgnoreCase(payment.status()));
            if (stillProcessing) {
                log.debug("Mercado Pago payment is still pending; order={}", order.order());
                return;
            }

            PaymentSearchResult latest = results.getFirst();
            if ("rejected".equalsIgnoreCase(latest.status()) || "cancelled".equalsIgnoreCase(latest.status())) {
                transition(order, latest, PaymentStatus.NEGAR_SERVICO, "PAYMENT_NOT_APPROVED",
                        "Provider status: " + latest.status());
                finalStateObserved = true;
                return;
            }

            log.debug("Mercado Pago payment has non-final status {}; order={}", latest.status(), order.order());
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

        log.info("Payment status changed from polling; order={}, paymentId={}, status={}",
                order.order(), payment.paymentId(), status);
        publisher.publish(updated);
    }
}
