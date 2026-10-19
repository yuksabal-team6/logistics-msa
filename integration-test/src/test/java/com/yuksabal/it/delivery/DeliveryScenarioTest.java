package com.yuksabal.it.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DLV: 배송 시나리오 (노션 테스트 시나리오 창구)
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
        Response order = s.order(s.u.token(), 2).expect(201);
        UUID deliveryId = order.uuid("deliveryId");
        String deliveries = "/api/v1/deliveries/" + deliveryId;

        // 허브 → 허브 (서울 → 부산): 경로 기록 1건
        JsonNode routes = Gateway.get(deliveries + "/routes", s.master).expect(200).data();
        assertThat(routes).hasSize(1);
        String route = deliveries + "/routes/" + routes.get(0).path("routeLogId").asText() + "/status";
        Gateway.patch(route, s.master, Map.of("routeStatus", "IN_TRANSIT")).expect(200);
        Gateway.patch(route, s.master, Map.of("routeStatus", "ARRIVED", "actualDistanceKm", 325.5)).expect(200);

        // 허브 → 업체
        Gateway.patch(deliveries + "/status", s.master, Map.of("deliveryStatus", "DELIVERING")).expect(200);
        Response completed = Gateway.patch(deliveries + "/status", s.master, Map.of("deliveryStatus", "COMPLETED"));

        assertThat(completed.status()).as("%s", completed).isEqualTo(200);
        assertThat(completed.data().path("deliveryStatus").asText()).isEqualTo("COMPLETED");
        JsonNode orderAfter = Gateway.get("/api/v1/orders/" + order.uuid("orderId"), s.master).expect(200).data();
        assertThat(orderAfter.path("status").asText()).isEqualTo("COMPLETED");
    }
}
