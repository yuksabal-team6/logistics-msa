package com.yuksabal.it.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.yuksabal.it.support.Scenario.BUSAN_HUB;
import static com.yuksabal.it.support.Scenario.SEOUL_HUB;
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

    @Test
    @DisplayName("DLV-2. 다른 허브 주문의 배송 생성")
    void dlv2_다른_허브_주문의_배송_생성() {
        // 1. 회원이 A(서울) → B(부산)로 P 1개 주문
        Response order = s.order(s.u.token(), 1);

        assertThat(order.status()).as("%s", order).isEqualTo(201);
        assertThat(order.data().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.data().hasNonNull("deliveryId")).as("%s", order).isTrue();
        String deliveries = "/api/v1/deliveries/" + order.uuid("deliveryId");

        // 2. 배송 상세: READY, 서울 → 부산
        Response delivery = Gateway.get(deliveries, s.master);

        assertThat(delivery.status()).as("%s", delivery).isEqualTo(200);
        assertThat(delivery.data().path("deliveryStatus").asText()).isEqualTo("READY");
        assertThat(delivery.data().path("departureHubId").asText()).isEqualTo(SEOUL_HUB.toString());
        assertThat(delivery.data().path("destinationHubId").asText()).isEqualTo(BUSAN_HUB.toString());

        // 3. 경로 기록 목록: 허브 → 허브 1건, READY, 예상 거리·시간 있음
        Response routes = Gateway.get(deliveries + "/routes", s.master);

        assertThat(routes.status()).as("%s", routes).isEqualTo(200);
        assertThat(routes.data()).hasSize(1);
        JsonNode route = routes.data().get(0);
        assertThat(route.path("routeStatus").asText()).isEqualTo("READY");
        assertThat(route.hasNonNull("expectedDistanceKm")).as("%s", route).isTrue();
        assertThat(route.hasNonNull("expectedDurationMinutes")).as("%s", route).isTrue();
    }

    @Test
    @DisplayName("DLV-3. 같은 허브 주문의 배송 생성")
    void dlv3_같은_허브_주문의_배송_생성() {
        // 공통 준비에는 같은 허브 업체가 없어 서울 허브 수령 업체 C를 만든다 (한 회원은 업체 하나만 관리해 관리자 c도 새로 만든다)
        String username = "c" + s.suffix;
        UUID ownerC = Gateway.post("/api/v1/auth/register", null, Map.of(
                        "username", username,
                        "password", "ItUser12!",
                        "slackId", "it-" + username,
                        "email", username + "@it.test"))
                .expect(201).uuid("userId");
        Gateway.patch("/api/v1/users/" + ownerC + "/role", s.master, Map.of("role", "COMPANY")).expect(200);
        UUID companyC = Gateway.post("/api/v1/companies", s.master, Map.of(
                        "companyName", "[IT] 수령 업체 C " + s.suffix,
                        "hubId", SEOUL_HUB,
                        "ownerId", ownerC,
                        "address", "통합 테스트 주소"))
                .expect(201).uuid("companyId");

        // 1. 회원이 A(서울) → C(서울)로 P 1개 주문
        Map<String, Object> body = new HashMap<>(s.orderBody(1));
        body.put("requestCompanyId", companyC);
        Response order = Gateway.postWithIdempotencyKey("/api/v1/orders", s.u.token(), body, UUID.randomUUID());

        assertThat(order.status()).as("%s", order).isEqualTo(201);
        assertThat(order.data().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.data().hasNonNull("deliveryId")).as("%s", order).isTrue();
        String deliveries = "/api/v1/deliveries/" + order.uuid("deliveryId");

        // 2. 배송 상세: READY, 서울 → 서울
        Response delivery = Gateway.get(deliveries, s.master);

        assertThat(delivery.status()).as("%s", delivery).isEqualTo(200);
        assertThat(delivery.data().path("deliveryStatus").asText()).isEqualTo("READY");
        assertThat(delivery.data().path("departureHubId").asText()).isEqualTo(SEOUL_HUB.toString());
        assertThat(delivery.data().path("destinationHubId").asText()).isEqualTo(SEOUL_HUB.toString());

        // 3. 경로 기록 목록: 같은 허브라 0건
        Response routes = Gateway.get(deliveries + "/routes", s.master);

        assertThat(routes.status()).as("%s", routes).isEqualTo(200);
        assertThat(routes.data()).as("%s", routes).isEmpty();
    }
}
