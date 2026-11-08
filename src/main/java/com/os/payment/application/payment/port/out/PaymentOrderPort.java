package com.os.payment.application.payment.port.out;

import java.util.List;
import java.util.Optional;
import com.os.payment.domain.PaymentOrder;

public interface PaymentOrderPort {
    Optional<PaymentOrder> findByOrderId(String orderId);
    List<PaymentOrder> findPendingOrders(int limit);
    PaymentOrder save(PaymentOrder order);
    boolean saveIfPending(PaymentOrder order);
}
