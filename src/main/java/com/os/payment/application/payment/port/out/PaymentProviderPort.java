package com.os.payment.application.payment.port.out;

import com.os.payment.application.payment.PaymentOrderRequest;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentProviderPort {
    Preference createPreference(PaymentOrderRequest order);
    List<PaymentSearchResult> searchPayments(String order);

    record Preference(String id, String checkoutUrl) {}
    record PaymentSearchResult(String paymentId, String externalReference, BigDecimal amount,
                               String currency, String status) {}
}
