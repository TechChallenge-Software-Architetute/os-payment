package com.os.payment.adapter.out.persistence.payment;

import com.os.payment.domain.PaymentOrder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Mapper(componentModel = "spring")
public interface PaymentOrderMapper {
    @Mapping(target = "status", source = "action")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "toInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "toInstant")
    PaymentOrder toDomain(PaymentOrderDocument document);

    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "toString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "toString")
    @Mapping(target = "action", source = "status")
    PaymentOrderDocument toDocument(PaymentOrder order);

    @Named("toInstant")
    default java.time.Instant toInstant(String value) {
        return value == null ? null : java.time.Instant.parse(value);
    }

    @Named("toString")
    default String toString(java.time.Instant value) {
        return value == null ? null : DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC).format(value);
    }
}
