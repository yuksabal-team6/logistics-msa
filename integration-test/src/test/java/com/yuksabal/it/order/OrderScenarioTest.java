package com.yuksabal.it.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Concurrently;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yuksabal.it.support.Scenario.BUSAN_HUB;
import static com.yuksabal.it.support.Scenario.INITIAL_STOCK;
import static com.yuksabal.it.support.Scenario.SEOUL_HUB;
import static org.assertj.core.api.Assertions.assertThat;

// ORD: 주문 시나리오 (노션 테스트 시나리오 창구)
// 테스트 본문은 노션 본문과 같은 순서로 쓴다: 사전 조건 → 이렇게 하면 → 이렇게 돼야 한다
// 시나리오마다 공통 준비(업체 A·B, 상품 P, 재고 10개, 회원 u·a·b)를 새로 만든다 (Scenario 참고)
class OrderScenarioTest {

    private Scenario s;

    @BeforeEach
    void setUp() {
        s = new Scenario();
    }

    @Test
    @DisplayName("ORD-1. 회원 주문 생성: CONFIRMED, 재고 차감, 서울 → 부산 배송 생성")
    void ord1_회원_주문_생성() {
        // 이렇게 하면: u가 P를 2개 주문
        Response order = s.order(s.u.token(), 2);

        // 이렇게 돼야 한다
        // 1. order: 201 CONFIRMED, 주문 번호 형식 ORD-yyyyMMdd-12자리
        assertThat(order.status()).as("%s", order).isEqualTo(201);
        assertThat(order.data().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.data().path("orderNumber").asText()).matches("ORD-\\d{8}-[0-9A-Z]{12}");
        // 2. hub: 서울 허브 재고 10 → 8
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK - 2);
        // 3. delivery: 공급 업체 허브(서울) → 요청 업체 허브(부산) 배송이 READY로 생성
        JsonNode delivery = s.delivery(order.uuid("deliveryId"));
        assertThat(delivery.path("departureHubId").asText()).isEqualTo(SEOUL_HUB.toString());
        assertThat(delivery.path("destinationHubId").asText()).isEqualTo(BUSAN_HUB.toString());
        assertThat(delivery.path("deliveryStatus").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("ORD-2. 요청 업체 관리자 주문 취소: CANCELLED + 보상 COMPLETED, 재고 복원, 배송 취소")
    void ord2_요청_업체_관리자_주문_취소() {
        // 사전 조건: u의 CONFIRMED 주문 (배송 출발 전)
        Response order = s.order(s.u.token(), 2).expect(201);

        // 이렇게 하면: 요청 업체 B의 관리자 b가 취소
        Response cancel = s.cancel(order.uuid("orderId"), s.b.token());

        // 이렇게 돼야 한다
        // 1. order: CANCELLED, 보상(재고 복원·배송 취소) COMPLETED
        assertThat(cancel.status()).as("%s", cancel).isEqualTo(200);
        assertThat(cancel.data().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancel.data().path("compensationStatus").asText()).isEqualTo("COMPLETED");
        // 2. hub: 재고 10개로 복원
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        // 3. delivery: 배송 CANCELLED
        assertThat(s.delivery(order.uuid("deliveryId")).path("deliveryStatus").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("ORD-3. 완료 주문 삭제: 200, 이후 조회 404")
    void ord3_완료_주문_삭제() {
        // 사전 조건: DLV-1처럼 배송을 끝까지 진행해 COMPLETED가 된 u의 주문 O
        // (배송 COMPLETED → delivery가 order 내부 API #10으로 알려 주문도 COMPLETED. CONFIRMED 주문은 삭제할 수 없다)
        Response order = s.order(s.u.token(), 2).expect(201);
        UUID orderId = order.uuid("orderId");
        s.completeDelivery(order.uuid("deliveryId"));
        assertThat(s.orderDetail(orderId).path("status").asText()).as("사전 조건: O의 상태").isEqualTo("COMPLETED");

        // 이렇게 하면
        // 1. MASTER가 O를 삭제
        Response delete = Gateway.delete("/api/v1/orders/" + orderId, s.master);
        // 2. O를 조회
        Response after = Gateway.get("/api/v1/orders/" + orderId, s.master);

        // 이렇게 돼야 한다
        // 1. 200
        assertThat(delete.status()).as("%s", delete).isEqualTo(200);
        // 2. 삭제된 주문은 조회되지 않는다: 404 RESOURCE_NOT_FOUND
        assertThat(after.status()).as("%s", after).isEqualTo(404);
        assertThat(after.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("ORD-4. 재고보다 많은 수량 주문: hub 409 그대로, 주문 FAILED + 보상 불필요, 재고 그대로")
    void ord4_재고보다_많은_수량_주문() {
        // 이렇게 하면: 재고(10)보다 1개 많이 주문
        Response order = s.order(s.u.token(), INITIAL_STOCK + 1);

        // 이렇게 돼야 한다
        // 1. hub의 재고 부족(409 INSUFFICIENT_STOCK)을 order가 코드·형식 그대로 전달
        assertThat(order.status()).as("%s", order).isEqualTo(409);
        assertThat(order.errorCode()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(order.errorFields()).containsExactly("quantity");
        // 2. hub: 재고 그대로
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        // 3. order: PENDING으로 저장된 뒤 FAILED로 남는다. 재고가 빠지지 않았으니 되돌릴 것도 없다(NOT_REQUIRED)
        //    → PENDING으로 남으면 회원·상품 삭제가 "진행 중 주문"으로 계속 막힌다 (CMP-2)
        JsonNode orders = s.ordersOf(s.u.token());
        assertThat(orders).as("u의 주문 목록").hasSize(1);
        assertThat(orders.get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(orders.get(0).path("compensationStatus").asText()).isEqualTo("NOT_REQUIRED");
    }

    @Test
    @DisplayName("ORD-5. 다른 업체 관리자 주문 취소: 403, 주문·재고·배송 그대로")
    void ord5_다른_업체_관리자_주문_취소() {
        // 사전 조건: u의 CONFIRMED 주문 (재고 8개)
        Response order = s.order(s.u.token(), 2).expect(201);

        // 이렇게 하면: 공급 업체 A의 관리자 a가 취소
        // (취소는 주문자, 요청 업체 관리자, MASTER만 가능. 요청 업체 관리자인지는 order가 company #2의 ownerId로 확인한다
        //  → 주문과 관련된 공급 업체 관리자라도 요청 업체 관리자가 아니면 막혀야 한다)
        Response cancel = s.cancel(order.uuid("orderId"), s.a.token());

        // 이렇게 돼야 한다
        // 1. 403
        assertThat(cancel.status()).as("%s", cancel).isEqualTo(403);
        assertThat(cancel.errorMessage()).isEqualTo("주문자, 요청 업체 관리자, MASTER만 주문을 취소할 수 있습니다.");
        // 2. 거절됐으니 보상(재고 복원·배송 취소)도 일어나지 않는다: 주문 CONFIRMED, 재고 8개, 배송 READY 그대로
        assertThat(s.orderDetail(order.uuid("orderId")).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK - 2);
        assertThat(s.delivery(order.uuid("deliveryId")).path("deliveryStatus").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("ORD-16. 정지된 회원 주문: 403, 주문 저장 안 됨, 재고 그대로")
    void ord16_정지된_회원_주문() {
        // 사전 조건: u의 토큰을 먼저 받은 뒤 u를 정지
        // (정지 전에 받은 토큰은 만료 전까지 게이트웨이를 통과한다 → order가 user #8로 활성 여부를 다시 확인해야 막힌다.
        //  순서를 바꾸면 로그인에서 막혀 이 경로를 확인할 수 없다)
        String token = s.u.token();
        s.deactivateUser(s.u);

        // 이렇게 하면: 정지 전 토큰으로 주문
        Response order = s.order(token, 2);

        // 이렇게 돼야 한다
        // 1. 403
        assertThat(order.status()).as("%s", order).isEqualTo(403);
        assertThat(order.errorMessage()).isEqualTo("비활성화된 회원은 주문할 수 없습니다.");
        // 2. hub: 재고 그대로
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        // 3. order: 주문자 확인이 저장 전이라 주문이 남지 않는다 (ORD-4는 저장 후 실패라 FAILED로 남는 것과 다름)
        assertThat(s.ordersOf(token)).as("u의 주문 목록").isEmpty();
    }

    @Test
    @DisplayName("ORD-17. 공급 업체 상품이 아닌 상품으로 주문: 400, 주문 저장 안 됨, 재고 그대로")
    void ord17_공급_업체_상품이_아닌_상품으로_주문() {
        // 사전 조건: A의 상품 P를 공급 업체 B로 적은 요청 (기본 본문을 복사해 한 필드만 바꾼다)
        Map<String, Object> body = new HashMap<>(s.orderBody(2));
        body.put("supplierCompanyId", s.companyB);

        // 이렇게 하면: u가 주문
        Response order = Gateway.postWithIdempotencyKey("/api/v1/orders", s.u.token(), body, UUID.randomUUID());

        // 이렇게 돼야 한다
        // 1. order가 company #1 응답의 companyId(A)와 요청의 공급 업체(B)를 비교해 400
        assertThat(order.status()).as("%s", order).isEqualTo(400);
        assertThat(order.errorMessage()).isEqualTo("공급 업체의 상품이 아닙니다.");
        assertThat(order.errorFields()).containsExactly("productId");
        // 2. 재고 그대로, 주문 저장 안 됨
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        assertThat(s.ordersOf(s.u.token())).as("u의 주문 목록").isEmpty();
    }

    // 실패: 취소가 잠깐 CANCELLED로 저장한 사이 배송 완료 알림(#10)이 거절되고, 취소도 되돌려져 주문이 CONFIRMED로 남는다 (#196)
    @Disabled("#196 배송 완료와 주문 취소가 동시에 오면 주문이 CONFIRMED로 남음")
    @Test
    @DisplayName("ORD-22. 배송 완료와 주문 취소 동시: 주문과 배송 상태가 서로 맞음 (배송 출발 후라 취소 409, 배송·주문 COMPLETED)")
    void ord22_배송_완료와_주문_취소_동시() {
        // 사전 조건: 공통 준비, u의 CONFIRMED 주문 O, 배송이 업체 배송 중(DELIVERING)
        // 경합은 한 번에 안 날 수 있어 주문 5건(P 1개씩)으로 5번 반복한다
        String uToken = s.u.token();
        int rounds = 5;

        for (int round = 1; round <= rounds; round++) {
            Response order = s.order(uToken, 1).expect(201);
            UUID orderId = order.uuid("orderId");
            UUID deliveryId = order.uuid("deliveryId");
            s.startDelivering(deliveryId);

            // 이렇게 하면: 배송 완료(MASTER)와 u의 주문 취소를 동시에 보낸다
            List<Response> responses = Concurrently.run(
                    () -> s.requestDeliveryComplete(deliveryId),
                    () -> s.cancel(orderId, uToken));
            Response complete = responses.get(0);
            Response cancel = responses.get(1);

            // 이렇게 돼야 한다: 주문과 배송 상태가 둘 중 하나로 맞아야 한다
            // - 배송 COMPLETED + 주문 COMPLETED, 재고 그대로 (취소 409)
            // - 배송 CANCELLED + 주문 CANCELLED, 재고 복원 (완료 409)
            // 배송은 허브 출발 전(READY)에만 취소할 수 있어, DELIVERING인 지금은 앞쪽만 가능하다
            // 1. 배송 완료 200, 취소 409 (order가 CANCELLED로 바꾼 뒤 delivery가 배송 취소를 거절 → CONFIRMED로 되돌림)
            assertThat(complete.status()).as("%d회차 %s", round, complete).isEqualTo(200);
            assertThat(cancel.status()).as("%d회차 %s", round, cancel).isEqualTo(409);
            // 2. delivery: COMPLETED
            assertThat(s.delivery(deliveryId).path("deliveryStatus").asText()).as("%d회차 배송", round).isEqualTo("COMPLETED");
            // 3. order: COMPLETED (CONFIRMED로 남으면, 취소 중 잠깐 CANCELLED였던 사이에 배송 완료 알림(#10)이 거절된 것)
            assertThat(s.orderDetail(orderId).path("status").asText()).as("%d회차 주문", round).isEqualTo("COMPLETED");
        }
        // 4. hub: 취소가 반영되지 않았으니 재고는 주문한 만큼만 빠진 채 그대로 (10 − 1 × 5)
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK - rounds);
    }
}
