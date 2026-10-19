package com.yuksabal.it.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.yuksabal.it.support.Scenario.BUSAN_HUB;
import static com.yuksabal.it.support.Scenario.INITIAL_STOCK;
import static com.yuksabal.it.support.Scenario.SEOUL_HUB;
import static org.assertj.core.api.Assertions.assertThat;

// ORD: 주문 시나리오 (노션 테스트 시나리오 창구)
// 시나리오마다 공통 준비(업체 A·B, 상품 P, 재고 10개, 회원 u·a·b)를 새로 만든다
class OrderScenarioTest {

    private Scenario s;

    @BeforeEach
    void setUp() {
        s = new Scenario();
    }

    @Test
    @DisplayName("ORD-1. 회원 주문 생성: CONFIRMED, 재고 차감, 서울 → 부산 배송 생성")
    void ord1_회원_주문_생성() {
        Response order = s.order(s.u.token(), 2);

        assertThat(order.status()).as("%s", order).isEqualTo(201);
        assertThat(order.data().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.data().path("orderNumber").asText()).matches("ORD-\\d{8}-[0-9A-Z]{12}");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK - 2);

        JsonNode delivery = Gateway.get("/api/v1/deliveries/" + order.uuid("deliveryId"), s.master).expect(200).data();
        assertThat(delivery.path("departureHubId").asText()).isEqualTo(SEOUL_HUB.toString());
        assertThat(delivery.path("destinationHubId").asText()).isEqualTo(BUSAN_HUB.toString());
        assertThat(delivery.path("deliveryStatus").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("ORD-2. 요청 업체 관리자 주문 취소: CANCELLED + 보상 COMPLETED, 재고 복원, 배송 취소")
    void ord2_요청_업체_관리자_주문_취소() {
        Response order = s.order(s.u.token(), 2).expect(201);
        UUID orderId = order.uuid("orderId");

        Response cancel = Gateway.post("/api/v1/orders/" + orderId + "/cancel", s.b.token(), null);

        assertThat(cancel.status()).as("%s", cancel).isEqualTo(200);
        assertThat(cancel.data().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancel.data().path("compensationStatus").asText()).isEqualTo("COMPLETED");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        JsonNode delivery = Gateway.get("/api/v1/deliveries/" + order.uuid("deliveryId"), s.master).expect(200).data();
        assertThat(delivery.path("deliveryStatus").asText()).isEqualTo("CANCELLED");
    }
}
