package com.os.payment.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.os.payment.application.payment.GeneratePaymentUseCase;
import com.os.payment.application.payment.PollPendingPaymentsUseCase;
import com.os.payment.application.payment.PaymentOrderRequest;
import com.os.payment.application.payment.port.out.PaymentOrderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort;
import com.os.payment.application.payment.port.out.PaymentStatusPublisherPort;
import com.os.payment.domain.PaymentOrder;
import com.os.payment.domain.PaymentStatus;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class PaymentSteps {
    private InMemoryOrders orders;
    private FakePaymentProvider provider;
    private RecordingPublisher publisher;
    private GeneratePaymentUseCase generatePayment;
    private PollPendingPaymentsUseCase pollPendingPayments;
    private PaymentOrderRequest request;
    private String order;

    @Before
    public void setUp() {
        orders = new InMemoryOrders();
        provider = new FakePaymentProvider();
        publisher = new RecordingPublisher();
        generatePayment = new GeneratePaymentUseCase(orders, provider, publisher);
        pollPendingPayments = new PollPendingPaymentsUseCase(orders, provider, publisher, 50);
    }

    @Given("que não existe pagamento para o pedido {string}")
    public void noPaymentExists(String id) {
        order = id;
    }

    @Given("o pedido tem total {string}")
    public void orderDetails(String amount) {
        request = new PaymentOrderRequest(order, new BigDecimal(amount), "correlation-test-123");
    }

    @Given("o Mercado Pago cria o checkout {string} com identificador {string}")
    public void mercadoPagoCreatesCheckout(String url, String preferenceId) {
        provider.preference = new PaymentProviderPort.Preference(preferenceId, url);
    }

    @Given("que já existe pagamento gerado para o pedido {string}")
    public void paymentAlreadyGenerated(String id) {
        order = id;
        orders.seed(order(id, PaymentStatus.ORDER_GENERATED));
    }

    @Given("o Mercado Pago falha ao criar o checkout")
    public void mercadoPagoFailsToCreateCheckout() {
        provider.preferenceFailure = new IllegalStateException("Checkout indisponível");
    }

    @Given("que existe checkout gerado para o pedido {string}")
    public void generatedCheckoutExists(String id) {
        order = id;
        orders.seed(order(id, PaymentStatus.ORDER_GENERATED));
    }

    @Given("o Mercado Pago retorna o pagamento {string} com status {string}, valor {string} e moeda {string}")
    public void mercadoPagoReturnsPayment(String paymentId, String status, String amount, String currency) {
        provider.payments = List.of(new PaymentProviderPort.PaymentSearchResult(paymentId, order,
                new BigDecimal(amount), currency, status));
    }

    @When("o pedido é processado")
    public void processOrder() { generatePayment.execute(request); }

    @When("o pedido é processado novamente")
    public void processOrderAgain() {
        generatePayment.execute(new PaymentOrderRequest(order, new BigDecimal("125.50"), "correlation-test-123"));
    }

    @When("os pagamentos pendentes são consultados")
    public void pollPendingPayments() { pollPendingPayments.pollPendingPayments(); }

    @Then("a ordem deve ser salva com status {string}")
    public void orderShouldBeSavedWithStatus(String expected) {
        assertTrue(!orders.savedOrders.isEmpty(), "Nenhuma ordem foi salva");
        assertEquals(PaymentStatus.valueOf(expected), orders.savedOrders.getLast().status());
    }

    @Then("a ordem deve permanecer com status {string}")
    public void orderShouldRemainWithStatus(String expected) {
        assertEquals(PaymentStatus.valueOf(expected), orders.findByOrder(order).orElseThrow().status());
    }

    @Then("o checkout {string} deve ser publicado para a ordem")
    public void checkoutShouldBePublished(String url) {
        assertEquals(1, publisher.publishedOrders.size());
        PaymentOrder published = publisher.publishedOrders.getFirst();
        assertEquals(order, published.order());
        assertEquals(url, published.checkoutUrl());
    }

    @Then("a criação do checkout não deve ser chamada")
    public void checkoutCreationShouldNotBeCalled() {
        assertEquals(0, provider.preferenceCalls);
        assertEquals(0, orders.savedOrders.size());
    }

    @Then("nenhum status deve ser publicado")
    public void noStatusShouldBePublished() { assertTrue(publisher.publishedOrders.isEmpty()); }

    @Then("um erro de pagamento deve ser publicado")
    public void paymentErrorShouldBePublished() {
        assertEquals(PaymentStatus.NEGAR_SERVICO, publisher.publishedOrders.getFirst().status());
        assertNotNull(publisher.publishedOrders.getFirst().errorCode());
    }

    @Then("o status {string} deve ser publicado")
    public void statusShouldBePublished(String expected) {
        assertEquals(PaymentStatus.valueOf(expected), publisher.publishedOrders.getFirst().status());
    }

    private PaymentOrder order(String id, PaymentStatus status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PaymentOrder(id, "correlation-test-123", new BigDecimal("125.50"), "BRL", status,
                "pref-abc", "https://mp.example/checkout/abc", null, null, now, now);
    }

    private static class InMemoryOrders implements PaymentOrderPort {
        private final Map<String, PaymentOrder> byOrder = new HashMap<>();
        private final List<PaymentOrder> savedOrders = new ArrayList<>();

        private void seed(PaymentOrder order) { byOrder.put(order.order(), order); }

        @Override
        public Optional<PaymentOrder> findByOrder(String id) { return Optional.ofNullable(byOrder.get(id)); }

        @Override
        public List<PaymentOrder> findPendingOrders(int limit) {
            return byOrder.values().stream().filter(order -> order.status() == PaymentStatus.ORDER_GENERATED)
                    .limit(limit).toList();
        }

        @Override
        public PaymentOrder save(PaymentOrder order) {
            byOrder.put(order.order(), order);
            savedOrders.add(order);
            return order;
        }

        @Override
        public boolean saveIfPending(PaymentOrder order) {
            PaymentOrder existing = byOrder.get(order.order());
            if (existing == null || existing.status() != PaymentStatus.ORDER_GENERATED) return false;
            save(order);
            return true;
        }
    }

    private static class FakePaymentProvider implements PaymentProviderPort {
        private Preference preference = new Preference("pref-default", "https://mp.example/checkout/default");
        private IllegalStateException preferenceFailure;
        private List<PaymentSearchResult> payments = List.of();
        private int preferenceCalls;

        @Override
        public Preference createPreference(PaymentOrderRequest order) {
            preferenceCalls++;
            if (preferenceFailure != null) throw preferenceFailure;
            return preference;
        }

        @Override
        public List<PaymentSearchResult> searchPayments(String id) {
            return payments.stream().filter(payment -> id.equals(payment.externalReference())).toList();
        }
    }

    private static class RecordingPublisher implements PaymentStatusPublisherPort {
        private final List<PaymentOrder> publishedOrders = new ArrayList<>();

        @Override
        public void publish(PaymentOrder order) { publishedOrders.add(order); }
    }
}
