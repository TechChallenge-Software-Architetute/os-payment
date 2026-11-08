package com.os.payment.adapter.in.schedule;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.os.payment.application.payment.PollPendingPaymentsUseCase;

@Component
public class PaymentPollingScheduler {
    private final PollPendingPaymentsUseCase useCase;

    public PaymentPollingScheduler(PollPendingPaymentsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${app.payment.polling.fixed-delay-ms:30000}")
    public void pollPayments() {
        useCase.pollPendingPayments();
    }
}
