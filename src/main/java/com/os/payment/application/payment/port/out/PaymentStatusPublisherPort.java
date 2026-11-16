package com.os.payment.application.payment.port.out;

import com.os.payment.domain.PaymentOrder;

public interface PaymentStatusPublisherPort {
    void publish(PaymentOrder order);
}
