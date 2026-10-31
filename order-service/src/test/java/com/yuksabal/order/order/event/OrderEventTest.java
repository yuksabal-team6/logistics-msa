package com.yuksabal.order.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 이벤트에 알림 값이 다 담기는지, Kafka JSON으로 오가도 값이 그대로인지 (#198)
class OrderEventTest {

    private final Order order = order();

    @Test
    void 주문_확정_이벤트에_알림_값이_다_담김() {
        OrderEvent event = OrderEvent.orderConfirmed(order, "생물 고등어", "공급 업체", "요청 업체", "U12345678");

        assertThat(event.eventType()).isEqualTo(OrderEventType.ORDER_CONFIRMED);
        assertThat(event.orderId()).isEqualTo(order.getId());
        assertThat(event.orderNumber()).isEqualTo("ORD-20261020-ABCDEF123456");
        assertThat(event.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(event.quantity()).isEqualTo(5);
        assertThat(event.unitPrice()).isEqualTo(12_000L);
        assertThat(event.totalAmount()).isEqualTo(60_000L);
        assertThat(event.requestMessage()).isEqualTo("신선도 유지 요청");
        assertThat(event.productName()).isEqualTo("생물 고등어");
        assertThat(event.supplierName()).isEqualTo("공급 업체");
        assertThat(event.requesterName()).isEqualTo("요청 업체");
        assertThat(event.ordererSlackId()).isEqualTo("U12345678");
        assertThat(event.reason()).isNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void 수동_처리_이벤트에_사유와_보상_상태가_담김() {
        ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELLED);
        ReflectionTestUtils.setField(order, "compensationStatus", CompensationStatus.FAILED);

        OrderEvent event = OrderEvent.manualActionRequired(order, "주문 취소 보상 실패 (재고 복원)");

        assertThat(event.eventType()).isEqualTo(OrderEventType.MANUAL_ACTION_REQUIRED);
        assertThat(event.reason()).isEqualTo("주문 취소 보상 실패 (재고 복원)");
        assertThat(event.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(event.compensationStatus()).isEqualTo(CompensationStatus.FAILED);
        assertThat(event.productName()).isNull();
    }

    // Kafka JsonSerializer, JsonDeserializer가 쓰는 ObjectMapper와 같은 설정
    @Test
    void JSON으로_보냈다_받아도_값이_같음() throws Exception {
        ObjectMapper mapper = JacksonUtils.enhancedObjectMapper();
        OrderEvent event = OrderEvent.orderConfirmed(order, "생물 고등어", "공급 업체", "요청 업체", "U12345678");

        OrderEvent back = mapper.readValue(mapper.writeValueAsString(event), OrderEvent.class);

        assertThat(back).isEqualTo(event);
    }

    private static Order order() {
        Order order = Order.builder()
                .requestCompanyId(UUID.randomUUID()).supplierCompanyId(UUID.randomUUID()).productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID()).arrivalHubId(UUID.randomUUID()).ordererId(UUID.randomUUID())
                .orderNumber("ORD-20261020-ABCDEF123456").quantity(5).unitPrice(12_000L)
                .requestMessage("신선도 유지 요청")
                .build();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.confirm(UUID.randomUUID());
        return order;
    }
}
