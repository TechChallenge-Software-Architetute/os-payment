package com.os.payment.application.payment.port.out;

import com.os.payment.domain.PaymentOrder;

import java.util.List;
import java.util.Optional;

public interface PaymentOrderPort {
    Optional<PaymentOrder> findByOrder(String order);
    List<PaymentOrder> findPendingOrders(int limit);
    PaymentOrder save(PaymentOrder order);
    boolean saveIfPending(PaymentOrder order);
}
