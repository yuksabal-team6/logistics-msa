package com.yuksabal.order.order.entity;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.order.global.exception.OrderErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void 완료_처리_CONFIRMED면_COMPLETED() {
        Order order = order();
        order.confirm(UUID.randomUUID());

        order.complete();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void 완료_처리_이미_COMPLETED면_변경_없음() {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", OrderStatus.COMPLETED);

        order.complete();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @ParameterizedTest(name = "{0} → 409")
    @CsvSource({"PENDING", "FAILED", "CANCELLED"})
    void 완료_처리_CONFIRMED가_아니면_409(OrderStatus status) {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", status);

        assertThatThrownBy(order::complete)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @Test
    void 취소_CONFIRMED면_CANCELLED_보상_PENDING() {
        Order order = order();
        order.confirm(UUID.randomUUID());

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCompensationStatus()).isEqualTo(CompensationStatus.PENDING);
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
