package com.os.payment.application.payment.port.out;

import java.math.BigDecimal;
import java.util.List;
import com.os.payment.application.payment.PaymentOrderRequest;

public interface PaymentProviderPort {
    Preference createPreference(PaymentOrderRequest order);
    List<PaymentSearchResult> searchPayments(String orderId);

    record Preference(String id, String checkoutUrl) {}
    record PaymentSearchResult(String paymentId, String externalReference, BigDecimal amount,
                               String currency, String status) {}
}
