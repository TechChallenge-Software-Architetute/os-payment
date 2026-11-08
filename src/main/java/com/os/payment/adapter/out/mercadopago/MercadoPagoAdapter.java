package com.os.payment.adapter.out.mercadopago;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.os.payment.application.payment.PaymentOrderRequest;
import com.os.payment.application.payment.port.out.PaymentProviderPort;
import com.os.payment.application.payment.port.out.PaymentProviderPort.Preference;
import com.os.payment.application.payment.port.out.PaymentProviderPort.PaymentSearchResult;

@Component
public class MercadoPagoAdapter implements PaymentProviderPort {
    private static final Logger log = LoggerFactory.getLogger(MercadoPagoAdapter.class);
    private final RestClient client;

    public MercadoPagoAdapter(@Value("${mercadopago.access-token:}") String token,
                              @Value("${mercadopago.api-url:https://api.mercadopago.com}") String apiUrl) {
        this.client = RestClient.builder().baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    @SuppressWarnings("unchecked")
    public Preference createPreference(PaymentOrderRequest order) {
        log.debug("Creating Mercado Pago checkout preference; order={}", order.order());
        List<Map<String, Object>> items = List.of(Map.of("title", "Pedido " + order.order(), "quantity", 1,
                "unit_price", order.amount(), "currency_id", "BRL"));
        Map<String, Object> body = Map.of("items", items, "external_reference", order.order());
        Map<String, Object> response;
        try {
            response = client.post().uri("/checkout/preferences").body(body).retrieve().body(Map.class);
        } catch (RuntimeException e) {
            if (e instanceof RestClientResponseException responseException) {
                log.error("Mercado Pago checkout request failed; order={}, httpStatus={}, errorType={}",
                        order.order(), responseException.getStatusCode().value(), e.getClass().getSimpleName());
            } else {
                log.error("Mercado Pago checkout request failed; order={}, errorType={}",
                        order.order(), e.getClass().getSimpleName());
            }
            throw e;
        }
        if (response == null || response.get("id") == null) throw new IllegalStateException("Mercado Pago returned an empty preference");
        log.debug("Mercado Pago checkout preference response received; order={}", order.order());
        return new Preference(response.get("id").toString(), String.valueOf(response.getOrDefault("init_point", "")));
    }

    @SuppressWarnings("unchecked")
    public List<PaymentSearchResult> searchPayments(String order) {
        log.debug("Searching Mercado Pago payments by external reference; order={}", order);
        Map<String, Object> response;
        try {
            response = client.get().uri(uriBuilder -> uriBuilder.path("/v1/payments/search")
                    .queryParam("sort", "date_last_updated")
                    .queryParam("criteria", "desc")
                    .queryParam("external_reference", order)
                    .queryParam("range", "date_last_updated")
                    .queryParam("begin_date", "NOW-364DAYS")
                    .queryParam("end_date", "NOW")
                    .queryParam("limit", 50)
                    .build()).retrieve().body(Map.class);
        } catch (RuntimeException e) {
            if (e instanceof RestClientResponseException responseException) {
                log.error("Mercado Pago payment search failed; order={}, httpStatus={}, errorType={}",
                        order, responseException.getStatusCode().value(), e.getClass().getSimpleName());
            } else {
                log.error("Mercado Pago payment search failed; order={}, errorType={}",
                        order, e.getClass().getSimpleName());
            }
            throw e;
        }
        if (response == null || !(response.get("results") instanceof List<?> records)) return List.of();

        List<PaymentSearchResult> payments = new ArrayList<>();
        for (Object item : records) {
            if (!(item instanceof Map<?, ?> record)) continue;
            payments.add(new PaymentSearchResult(value(record.get("id")), value(record.get("external_reference")),
                    decimal(record.get("transaction_amount")), value(record.get("currency_id")),
                    value(record.get("status"))));
        }
        log.debug("Mercado Pago payment search completed; order={}, results={}", order, payments.size());
        return payments;
    }

    private String value(Object value) { return value == null ? null : value.toString(); }

    private BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()); }

}
