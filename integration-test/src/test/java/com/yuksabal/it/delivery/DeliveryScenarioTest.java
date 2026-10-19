package com.yuksabal.it.delivery;

import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DLV: 배송 시나리오 (노션 테스트 시나리오 창구)
// 테스트 본문은 노션 본문과 같은 순서로 쓴다: 사전 조건 → 이렇게 하면 → 이렇게 돼야 한다
// 배송·경로 기록 상태는 MASTER가 바꾼다 (MASTER는 담당자가 아니어도 변경 가능)
class DeliveryScenarioTest {

    private Scenario s;

    @BeforeEach
    void setUp() {
        s = new Scenario();
    }

    @Test
    @DisplayName("DLV-1. 배송 완료 시 주문 완료: 경로 도착 → 업체 배송 완료 → 주문 COMPLETED")
    void dlv1_배송_완료_시_주문_완료() {
        // 사전 조건: u의 CONFIRMED 주문 (서울 → 부산, 경로 기록 1건)
        Response order = s.order(s.u.token(), 2).expect(201);
        UUID deliveryId = order.uuid("deliveryId");

        // 이렇게 하면: 경로 기록 IN_TRANSIT → ARRIVED, 배송 DELIVERING → COMPLETED (각 단계 200이 아니면 바로 실패)
        s.completeDelivery(deliveryId);

        // 이렇게 돼야 한다
        // 1. delivery: 배송 COMPLETED
        assertThat(s.delivery(deliveryId).path("deliveryStatus").asText()).isEqualTo("COMPLETED");
        // 2. order: delivery가 배송 완료를 알려(내부 API #10) 주문도 COMPLETED
        assertThat(s.orderDetail(order.uuid("orderId")).path("status").asText()).isEqualTo("COMPLETED");
    }
}
