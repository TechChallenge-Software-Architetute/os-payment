package com.os.payment.adapter.out.persistence.payment;

import java.math.BigDecimal;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

@DynamoDbBean
public class PaymentOrderDocument {
    private String order;
    private String correlationId;
    private BigDecimal amount;
    private String currency;
    private String action;
    private String providerPaymentId;
    private String checkoutUrl;
    private String errorCode;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;

    public PaymentOrderDocument() {}

    @DynamoDbPartitionKey
    public String getOrder() { return order; }
    public void setOrder(String order) { this.order = order; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    @DynamoDbSecondaryPartitionKey(indexNames = "action-updatedAt-index")
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public void setCheckoutUrl(String checkoutUrl) { this.checkoutUrl = checkoutUrl; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    @DynamoDbSecondarySortKey(indexNames = "action-updatedAt-index")
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
