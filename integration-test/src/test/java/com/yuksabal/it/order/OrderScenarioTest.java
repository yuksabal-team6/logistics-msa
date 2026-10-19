package com.yuksabal.it.order;

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

    @Test
    @DisplayName("ORD-4. 재고보다 많은 수량 주문: hub 409 그대로, 주문 FAILED + 보상 불필요, 재고 그대로")
    void ord4_재고보다_많은_수량_주문() {
        Response order = s.order(s.u.token(), INITIAL_STOCK + 1);

        // hub의 재고 부족(409 INSUFFICIENT_STOCK)을 order가 그대로 전달한다
        assertThat(order.status()).as("%s", order).isEqualTo(409);
        assertThat(order.error().path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(order.errorFields()).containsExactly("quantity");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);

        // 주문은 PENDING으로 저장된 뒤 FAILED로 남는다. 재고가 빠지지 않았으니 되돌릴 것도 없다
        JsonNode orders = Gateway.get("/api/v1/orders?size=100", s.u.token()).expect(200).data().path("items");
        assertThat(orders).as("u의 주문 목록").hasSize(1);
        assertThat(orders.get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(orders.get(0).path("compensationStatus").asText()).isEqualTo("NOT_REQUIRED");
    }

    @Test
    @DisplayName("ORD-5. 다른 업체 관리자 주문 취소: 403, 주문·재고·배송 그대로")
    void ord5_다른_업체_관리자_주문_취소() {
        Response order = s.order(s.u.token(), 2).expect(201);

        // a는 공급 업체 A의 관리자지만 요청 업체(B) 관리자가 아니라 취소할 수 없다
        // (취소는 주문자, 요청 업체 관리자, MASTER만. 요청 업체 관리자는 order가 company #2의 ownerId로 확인)
        Response cancel = Gateway.post("/api/v1/orders/" + order.uuid("orderId") + "/cancel", s.a.token(), null);

        assertThat(cancel.status()).as("%s", cancel).isEqualTo(403);
        assertThat(cancel.error().path("message").asText()).isEqualTo("주문자, 요청 업체 관리자, MASTER만 주문을 취소할 수 있습니다.");
        JsonNode orderAfter = Gateway.get("/api/v1/orders/" + order.uuid("orderId"), s.master).expect(200).data();
        assertThat(orderAfter.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK - 2);
        JsonNode delivery = Gateway.get("/api/v1/deliveries/" + order.uuid("deliveryId"), s.master).expect(200).data();
        assertThat(delivery.path("deliveryStatus").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("ORD-16. 정지된 회원 주문: 403, 주문 저장 안 됨, 재고 그대로")
    void ord16_정지된_회원_주문() {
        // 정지 전에 받은 토큰은 만료 전까지 게이트웨이를 통과한다 → order가 user #8로 활성 여부를 다시 확인해야 막힌다
        String token = s.u.token();
        Gateway.patch("/api/v1/users/" + s.u.userId() + "/status", s.master, Map.of("active", false)).expect(200);

        Response order = s.order(token, 2);

        assertThat(order.status()).as("%s", order).isEqualTo(403);
        assertThat(order.error().path("message").asText()).isEqualTo("비활성화된 회원은 주문할 수 없습니다.");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        JsonNode orders = Gateway.get("/api/v1/orders?size=100", token).expect(200).data().path("items");
        assertThat(orders).as("u의 주문 목록 (주문자 확인이 저장 전이라 남지 않음)").isEmpty();
    }

    @Test
    @DisplayName("ORD-17. 공급 업체 상품이 아닌 상품으로 주문: 400, 주문 저장 안 됨, 재고 그대로")
    void ord17_공급_업체_상품이_아닌_상품으로_주문() {
        // P는 A의 상품인데 공급 업체를 B로 적는다 → order가 company #1의 companyId와 비교해 막는다
        Map<String, Object> body = new HashMap<>(s.orderBody(2));
        body.put("supplierCompanyId", s.companyB);

        Response order = Gateway.postWithIdempotencyKey("/api/v1/orders", s.u.token(), body, UUID.randomUUID());

        assertThat(order.status()).as("%s", order).isEqualTo(400);
        assertThat(order.error().path("message").asText()).isEqualTo("공급 업체의 상품이 아닙니다.");
        assertThat(order.errorFields()).containsExactly("productId");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        JsonNode orders = Gateway.get("/api/v1/orders?size=100", s.u.token()).expect(200).data().path("items");
        assertThat(orders).as("u의 주문 목록").isEmpty();
    }
}
