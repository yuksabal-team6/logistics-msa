package com.yuksabal.order.order.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    private final UUID ordererId = UUID.randomUUID();

    @ParameterizedTest(name = "{0} + {1} → 삭제 가능 {2}")
    @CsvSource({
            "COMPLETED, NOT_REQUIRED, true",
            "FAILED, NOT_REQUIRED, true",
            "FAILED, COMPLETED, true",
            "CANCELLED, NOT_REQUIRED, true",
            "CANCELLED, COMPLETED, true",
            "FAILED, PENDING, false",
            "FAILED, FAILED, false",
            "CANCELLED, PENDING, false",
            "CANCELLED, FAILED, false",
            "PENDING, NOT_REQUIRED, false",
            "CONFIRMED, NOT_REQUIRED, false"
    })
    void 삭제_가능_상태(OrderStatus status, CompensationStatus compensationStatus, boolean expected) {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "compensationStatus", compensationStatus);

        assertThat(order.isDeletable()).isEqualTo(expected);
    }

    @Test
    void 주문자_본인_확인() {
        Order order = order();

        assertThat(order.isOrderedBy(ordererId)).isTrue();
        assertThat(order.isOrderedBy(UUID.randomUUID())).isFalse();
    }

    @Test
    void 생성하면_PENDING_NOT_REQUIRED_총액_계산() {
        Order order = order();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        assertThat(order.getTotalAmount()).isEqualTo(60_000L);
    }

    private Order order() {
        return Order.builder()
                .requestCompanyId(UUID.randomUUID())
                .supplierCompanyId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .arrivalHubId(UUID.randomUUID())
                .ordererId(ordererId)
                .orderNumber("TEST-1")
                .quantity(5)
                .unitPrice(12_000L)
                .build();
    }
}
