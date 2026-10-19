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
// 배송·경로 기록 상태 변경은 대부분 MASTER가 하고 (MASTER는 담당자가 아니어도 변경 가능),
// 담당자 권한이 필요한 시나리오(DLV-6)는 배송 기사가 한다
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

    @Test
    @DisplayName("DLV-4. 주문자만 배송 조회")
    void dlv4_주문자만_배송_조회() {
        String u = s.u.token();
        String b = s.b.token(); // 주문자가 아닌 수령 업체 B 관리자

        // 1. 회원 u가 A(서울) → B(부산)로 P 1개 주문
        Response order = s.order(u, 1).expect(201);
        String deliveryId = order.uuid("deliveryId").toString();
        String deliveries = "/api/v1/deliveries/" + deliveryId;

        // 2. u: 배송 상세, 경로 기록 목록, 경로 기록 상세 모두 200
        Response delivery = Gateway.get(deliveries, u);

        assertThat(delivery.status()).as("%s", delivery).isEqualTo(200);

        Response routes = Gateway.get(deliveries + "/routes", u);

        assertThat(routes.status()).as("%s", routes).isEqualTo(200);
        assertThat(routes.data()).as("%s", routes).hasSize(1);

        Response route = Gateway.get(deliveries + "/routes/" + routes.data().get(0).path("routeLogId").asText(), u);

        assertThat(route.status()).as("%s", route).isEqualTo(200);

        // 3. u: 배송 목록에 D 있음 (다른 배송이 섞여 있을 수 있어 개수는 보지 않는다)
        Response uList = Gateway.get("/api/v1/deliveries?size=100", u);

        assertThat(uList.status()).as("%s", uList).isEqualTo(200);
        assertThat(uList.data().path("items").findValuesAsText("deliveryId")).as("%s", uList).contains(deliveryId);

        // 4. b: 배송 상세, 경로 기록 목록 모두 403
        Response bDelivery = Gateway.get(deliveries, b);

        assertThat(bDelivery.status()).as("%s", bDelivery).isEqualTo(403);
        assertThat(bDelivery.error().path("code").asText()).isEqualTo("DELIVERY_ACCESS_DENIED");

        Response bRoutes = Gateway.get(deliveries + "/routes", b);

        assertThat(bRoutes.status()).as("%s", bRoutes).isEqualTo(403);
        assertThat(bRoutes.error().path("code").asText()).isEqualTo("DELIVERY_ACCESS_DENIED");

        // 5. b: 배송 목록에 D 없음
        Response bList = Gateway.get("/api/v1/deliveries?size=100", b);

        assertThat(bList.status()).as("%s", bList).isEqualTo(200);
        assertThat(bList.data().path("items").findValuesAsText("deliveryId")).as("%s", bList).doesNotContain(deliveryId);
    }

    @Test
    @DisplayName("DLV-5. USER 회원을 배송 담당자로 지정")
    void dlv5_USER_회원을_배송_담당자로_지정() {
        // 1. 회원 u가 A(서울) → B(부산)로 P 1개 주문
        Response order = s.order(s.u.token(), 1).expect(201);
        String deliveries = "/api/v1/deliveries/" + order.uuid("deliveryId");

        // 2. MASTER: D의 배송 담당자를 u(USER)로 지정 → user 내부 API로 역할을 확인해 422
        Response assign = Gateway.patch(deliveries + "/manager", s.master, Map.of("deliveryManagerId", s.u.userId()));

        assertThat(assign.status()).as("%s", assign).isEqualTo(422);
        assertThat(assign.error().path("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");

        // 3. MASTER: D 상세에 담당자가 지정되지 않음
        Response delivery = Gateway.get(deliveries, s.master);

        assertThat(delivery.status()).as("%s", delivery).isEqualTo(200);
        assertThat(delivery.data().hasNonNull("deliveryManagerId")).as("%s", delivery).isFalse();
    }

    @Test
    @DisplayName("DLV-6. 출발 후 주문 취소")
    void dlv6_출발_후_주문_취소() {
        // 배송 기사 d를 만든다 (역할을 바꾼 뒤 로그인해야 토큰에 DELIVERY가 담긴다)
        String username = "d" + s.suffix;
        UUID driverId = Gateway.post("/api/v1/auth/register", null, Map.of(
                        "username", username,
                        "password", "ItUser12!",
                        "slackId", "it-" + username,
                        "email", username + "@it.test"))
                .expect(201).uuid("userId");
        Gateway.patch("/api/v1/users/" + driverId + "/role", s.master, Map.of("role", "DELIVERY")).expect(200);
        String d = new Scenario.Member(driverId, username).token();

        // 1. 회원 u가 A(서울) → B(부산)로 P 1개 주문
        String u = s.u.token();
        Response order = s.order(u, 1).expect(201);
        UUID orderId = order.uuid("orderId");
        String deliveries = "/api/v1/deliveries/" + order.uuid("deliveryId");
        String route = deliveries + "/routes/"
                + Gateway.get(deliveries + "/routes", s.master).expect(200).data().get(0).path("routeLogId").asText();

        // 2. MASTER: R의 담당자를 d로 지정
        Gateway.patch(route + "/manager", s.master, Map.of("deliveryManagerId", driverId)).expect(200);

        // 3. d: R을 IN_TRANSIT으로 변경 (출발). 배송 D도 IN_TRANSIT이 된다
        Gateway.patch(route + "/status", d, Map.of("routeStatus", "IN_TRANSIT")).expect(200);

        // 4. 출발 허브(서울)의 P 재고를 기억
        int stockBefore = s.stockOfP();

        // 5. u: 주문 취소 → 경로 기록이 출발해 409
        Response cancel = Gateway.post("/api/v1/orders/" + orderId + "/cancel", u, null);

        assertThat(cancel.status()).as("%s", cancel).isEqualTo(409);
        assertThat(cancel.error().path("code").asText()).isEqualTo("INVALID_STATUS_TRANSITION");

        // 6. 주문은 CONFIRMED + 보상 NOT_REQUIRED로 되돌아가고, D는 IN_TRANSIT 그대로, 재고는 복원되지 않음
        Response orderAfter = Gateway.get("/api/v1/orders/" + orderId, u);

        assertThat(orderAfter.status()).as("%s", orderAfter).isEqualTo(200);
        assertThat(orderAfter.data().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(orderAfter.data().path("compensationStatus").asText()).isEqualTo("NOT_REQUIRED");

        Response delivery = Gateway.get(deliveries, s.master);

        assertThat(delivery.status()).as("%s", delivery).isEqualTo(200);
        assertThat(delivery.data().path("deliveryStatus").asText()).isEqualTo("IN_TRANSIT");
        assertThat(s.stockOfP()).isEqualTo(stockBefore);
    }
}
