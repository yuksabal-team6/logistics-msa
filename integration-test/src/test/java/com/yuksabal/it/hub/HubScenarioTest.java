package com.yuksabal.it.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Concurrently;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import com.yuksabal.it.support.Scenario.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.yuksabal.it.support.Scenario.INITIAL_STOCK;
import static org.assertj.core.api.Assertions.assertThat;

// HUB: 재고 시나리오 (노션 테스트 시나리오 창구)
// 테스트 본문은 노션 본문과 같은 순서로 쓴다: 사전 조건 → 이렇게 하면 → 이렇게 돼야 한다
// 시나리오마다 공통 준비(업체 A·B, 상품 P, 재고 10개, 회원 u·a·b)를 새로 만든다 (Scenario 참고)
//
// hub는 입고·재고 조회 때 company 내부 API(#1 상품)로 상품의 활성 여부·업체·관리 허브를 확인한다
// 동시 요청 시나리오(HUB-5, 7, 13, 14)는 어느 요청이 먼저 처리될지 매번 달라서, 성공·실패 건수와 최종 데이터(재고·이력·주문 상태)를 확인한다
class HubScenarioTest {

    private Scenario s;

    @BeforeEach
    void setUp() {
        s = new Scenario();
    }

    @Test
    @DisplayName("HUB-1. 비활성 상품 입고: 422(productId), 재고·입고 이력 그대로")
    void hub1_비활성_상품_입고() {
        // 사전 조건: 공통 준비 (서울 허브 P 재고 10개, 입고 이력 1건) + P 비활성화 (업체 A는 활성)
        String inventoryId = s.inventoryOfP().path("inventoryId").asText();
        s.deactivateProduct();

        // 이렇게 하면: MASTER가 서울 허브에 P를 5개 입고
        Response inbound = Gateway.post("/api/v1/inventories/inbound", s.master,
                Map.of("hubId", Scenario.SEOUL_HUB, "productId", s.productP, "quantity", 5));

        // 이렇게 돼야 한다
        // 1. hub가 company #1의 active=false를 보고 422
        assertThat(inbound.status()).as("%s", inbound).isEqualTo(422);
        assertThat(inbound.errorMessage()).isEqualTo("비활성 상품은 입고할 수 없습니다.");
        assertThat(inbound.errorFields()).containsExactly("productId");
        // 2. 재고 10개 그대로, 입고 이력도 처음 입고 1건 그대로
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        assertThat(s.changeTypesOf(inventoryId)).containsExactly("INBOUND");
    }

    @Test
    @DisplayName("HUB-2. 관리 허브가 아닌 허브로 첫 입고: 422(hubId), 재고 생성 안 됨")
    void hub2_관리_허브가_아닌_허브로_첫_입고() {
        // 사전 조건: 재고가 없는 공통 준비 (A의 관리 허브는 서울, P는 활성, 재고 행 없음)
        Scenario noStock = new Scenario(false);

        // 이렇게 하면: MASTER가 부산 허브에 P를 10개 입고
        Response inbound = Gateway.post("/api/v1/inventories/inbound", noStock.master,
                Map.of("hubId", Scenario.BUSAN_HUB, "productId", noStock.productP, "quantity", INITIAL_STOCK));

        // 이렇게 돼야 한다
        // 1. hub가 company #1의 hubId(업체 관리 허브 = 서울)와 요청 허브(부산)를 비교해 422, details에 관리 허브 ID
        assertThat(inbound.status()).as("%s", inbound).isEqualTo(422);
        assertThat(inbound.errorMessage()).isEqualTo("상품 업체의 관리 허브에만 입고할 수 있습니다.");
        assertThat(inbound.errorFields()).containsExactly("hubId");
        assertThat(inbound.error().path("details").get(0).path("reason").asText()).contains(Scenario.SEOUL_HUB.toString());
        // 2. P의 재고 행이 만들어지지 않음 (상품별 재고 조회 404)
        Response stock = Gateway.get("/api/v1/inventories/products/" + noStock.productP, noStock.master);
        assertThat(stock.status()).as("%s", stock).isEqualTo(404);
    }

    @Test
    @DisplayName("HUB-3. 다른 업체 상품의 재고 조회: 다른 업체 관리자 403, 상품 업체 관리자 200")
    void hub3_다른_업체_상품의_재고_조회() {
        // 사전 조건: 공통 준비 (A의 상품 P, 서울 허브 재고 10개, b는 B의 관리자)
        String path = "/api/v1/inventories/products/" + s.productP;

        // 이렇게 하면: B 관리자 b와 A 관리자 a가 각각 P의 재고를 조회
        Response byOther = Gateway.get(path, s.b.token());
        Response byOwner = Gateway.get(path, s.a.token());

        // 이렇게 돼야 한다
        // 1. b: hub가 재고의 업체(A)를 company 내부 API(#2)로 조회해 관리자(ownerId)와 요청자를 비교 → 403
        assertThat(byOther.status()).as("%s", byOther).isEqualTo(403);
        // 2. a: 자기 업체 상품이라 200, 재고 10개
        assertThat(byOwner.status()).as("%s", byOwner).isEqualTo(200);
        assertThat(byOwner.data().path("quantity").asInt()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    @DisplayName("HUB-5 · ORD-21. 마지막 재고에 주문 2건 동시: 1건 201, 1건 409(INSUFFICIENT_STOCK), 재고 0 (음수 없음)")
    void hub5_ord21_마지막_재고에_주문_2건_동시() {
        // 사전 조건: 재고가 없는 공통 준비 + 서울 허브에 P 1개 입고, 주문자 u·v (토큰은 출발 전에 미리 받는다)
        Scenario last = new Scenario(false);
        last.inbound(Scenario.SEOUL_HUB, 1);
        String inventoryId = last.inventoryOfP().path("inventoryId").asText();
        Member v = last.user("v");
        String uToken = last.u.token();
        String vToken = v.token();

        // 이렇게 하면: u와 v가 P를 1개씩 동시에 주문 (Idempotency-Key는 각자 다르게)
        List<Response> orders = Concurrently.run(() -> last.order(uToken, 1), () -> last.order(vToken, 1));

        // 이렇게 돼야 한다
        // 1. 하나는 201 CONFIRMED, 하나는 hub의 재고 부족(409 INSUFFICIENT_STOCK)을 order가 그대로 전달
        //    (hub가 재고 행을 잠그고(SELECT ... FOR UPDATE) 수량이 충분할 때만 차감해서, 뒤 요청은 0개를 보고 실패한다)
        assertThat(Concurrently.count(orders, 201)).as("%s", orders).isEqualTo(1);
        assertThat(Concurrently.count(orders, 409)).as("%s", orders).isEqualTo(1);
        Response failed = orders.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertThat(failed.errorCode()).isEqualTo("INSUFFICIENT_STOCK");
        // 2. hub: 재고 0 (음수가 아님), 차감 이력 1건
        assertThat(last.stockOfP()).isZero();
        assertThat(Collections.frequency(last.changeTypesOf(inventoryId), "DECREASE")).isEqualTo(1);
        // 3. order: CONFIRMED 1건, 재고 부족 쪽은 PENDING으로 저장된 뒤 FAILED + 보상 불필요(NOT_REQUIRED)로 남는다 (ORD-4와 같음)
        List<String> results = new ArrayList<>();
        for (String token : List.of(uToken, vToken)) {
            last.ordersOf(token).forEach(o -> results.add(o.path("status").asText() + " / " + o.path("compensationStatus").asText()));
        }
        assertThat(results).containsExactlyInAnyOrder("CONFIRMED / NOT_REQUIRED", "FAILED / NOT_REQUIRED");
    }

    @Test
    @DisplayName("HUB-7. 같은 허브에 상품 동시 첫 입고: 늦은 쪽 409(INVENTORY_CREATE_CONFLICT) 후 다시 입고, 재고 행 1개, 수량 15")
    void hub7_같은_허브에_상품_동시_첫_입고() {
        // 사전 조건: 재고가 없는 공통 준비 (A의 관리 허브는 서울, P는 활성, 재고 행 없음)
        Scenario noStock = new Scenario(false);
        String inbound = "/api/v1/inventories/inbound";
        Map<Integer, Map<String, Object>> bodies = Map.of(
                10, Map.of("hubId", Scenario.SEOUL_HUB, "productId", noStock.productP, "quantity", 10),
                5, Map.of("hubId", Scenario.SEOUL_HUB, "productId", noStock.productP, "quantity", 5));

        // 이렇게 하면
        // 1. MASTER 두 명(같은 토큰으로 대신)이 서울 허브에 P를 각각 10개, 5개 동시에 입고
        List<Response> first = Concurrently.run(
                () -> Gateway.post(inbound, noStock.master, bodies.get(10)),
                () -> Gateway.post(inbound, noStock.master, bodies.get(5)));
        // 2. 409를 받은 쪽이 같은 수량으로 다시 입고 (이번에는 이미 생긴 재고 행의 수량을 늘린다)
        List<Response> retries = new ArrayList<>();
        int[] quantities = {10, 5};
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).status() == 409) {
                retries.add(Gateway.post(inbound, noStock.master, bodies.get(quantities[i])));
            }
        }
        // 3. P 재고 조회 (상품으로 거른 재고 목록으로 행 수까지 확인)
        Response rows = Gateway.get("/api/v1/inventories?productId=" + noStock.productP, noStock.master);

        // 이렇게 돼야 한다
        // 1. 둘 다 재고 행을 새로 만들려다 (hub_id, product_id) 유니크 인덱스에 걸린 쪽은 500이 아니라 409 INVENTORY_CREATE_CONFLICT
        //    (먼저 만든 쪽이 커밋한 뒤 출발했다면 둘 다 200일 수도 있다 → 응답 조합이 아니라 최종 데이터로 확인한다)
        assertThat(first).as("%s", first).allSatisfy(r -> assertThat(r.status()).isIn(200, 409));
        first.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.errorCode()).isEqualTo("INVENTORY_CREATE_CONFLICT"));
        // 2. 다시 입고는 200
        assertThat(retries).as("%s", retries).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        // 3. 재고 행 1개, 수량 15, 입고 이력 2건
        assertThat(rows.status()).as("%s", rows).isEqualTo(200);
        assertThat(rows.data().path("items")).as("P의 재고 행").hasSize(1);
        JsonNode inventory = rows.data().path("items").get(0);
        assertThat(inventory.path("quantity").asInt()).isEqualTo(15);
        assertThat(noStock.changeTypesOf(inventory.path("inventoryId").asText())).containsExactly("INBOUND", "INBOUND");
    }

    @Test
    @DisplayName("HUB-10. 관리 허브 변경 후 상품별 재고 조회: 새 관리 허브의 재고 1건으로 응답 (이전 허브 0개 행은 이력)")
    void hub10_관리_허브_변경_후_상품별_재고_조회() {
        // 사전 조건: 공통 준비 (A는 서울 허브, 서울 허브 P 재고 10개)
        String seoulInventoryId = s.inventoryOfP().path("inventoryId").asText();

        // 이렇게 하면
        // 1. MASTER가 서울 허브 P 재고를 0으로 조정 (조회할 때 본 수량 10과 현재 수량이 같을 때만 조정된다)
        Response adjust = Gateway.patch("/api/v1/inventories/" + seoulInventoryId, s.master,
                Map.of("expectedQuantity", INITIAL_STOCK, "quantity", 0));
        // 2. MASTER가 A의 관리 허브를 부산으로 변경 (재고 0, 진행 중 주문 없음 → company의 남은 업무 확인 통과)
        Response change = Gateway.patch("/api/v1/companies/" + s.companyA, s.master, Map.of("hubId", Scenario.BUSAN_HUB));
        // 3. MASTER가 부산 허브에 P를 5개 입고 (hub는 company #1의 hubId가 부산으로 바뀐 것을 보고 허용)
        s.inbound(Scenario.BUSAN_HUB, 5);
        // 4. MASTER와 A 관리자가 P 재고 조회
        JsonNode byMaster = s.inventoryOfP();
        Response byOwner = Gateway.get("/api/v1/inventories/products/" + s.productP, s.a.token());

        // 이렇게 돼야 한다
        // 1. 조정 200, 서울 재고 0, 조정 이력(ADJUST_DECREASE) 추가
        assertThat(adjust.status()).as("%s", adjust).isEqualTo(200);
        assertThat(adjust.data().path("quantity").asInt()).isZero();
        assertThat(s.changeTypesOf(seoulInventoryId)).contains("INBOUND", "ADJUST_DECREASE");
        // 2. 관리 허브 변경 200
        assertThat(change.status()).as("%s", change).isEqualTo(200);
        // 3·4. P의 재고 행은 서울(0개, 이력)과 부산(5개) 두 개지만, 상품별 조회는 수량 있는 부산 1건으로 응답 (#162)
        assertThat(byMaster.path("hubId").asText()).isEqualTo(Scenario.BUSAN_HUB.toString());
        assertThat(byMaster.path("quantity").asInt()).isEqualTo(5);
        assertThat(byOwner.status()).as("%s", byOwner).isEqualTo(200);
        assertThat(byOwner.data().path("hubId").asText()).isEqualTo(Scenario.BUSAN_HUB.toString());
    }

    @Test
    @DisplayName("HUB-11. 재고 수량 조정 중 다른 변경이 끼어듦: 조회 때 본 수량으로 조정하면 409(INVENTORY_QUANTITY_CONFLICT), 현재 수량으로 다시 조정하면 200")
    void hub11_재고_수량_조정_중_다른_변경이_끼어듦() {
        // 사전 조건: 공통 준비 (서울 허브 P 재고 10개)
        String inventory = "/api/v1/inventories/";

        // 이렇게 하면
        // 1. MASTER가 P 재고를 조회 (수량 10)
        JsonNode seen = s.inventoryOfP();
        String inventoryId = seen.path("inventoryId").asText();
        // 2. 그 사이 u가 P를 3개 주문 (재고 7)
        Response order = s.order(s.u.token(), 3);
        // 3. MASTER가 1번에서 본 수량(10)을 기준으로 8개로 조정
        Response stale = Gateway.patch(inventory + inventoryId, s.master, Map.of("expectedQuantity", INITIAL_STOCK, "quantity", 8));
        // 4. MASTER가 현재 수량(7)을 기준으로 다시 8개로 조정
        Response retry = Gateway.patch(inventory + inventoryId, s.master, Map.of("expectedQuantity", 7, "quantity", 8));

        // 이렇게 돼야 한다
        // 1. 조회 수량 10
        assertThat(seen.path("quantity").asInt()).isEqualTo(INITIAL_STOCK);
        // 2. 주문 성공, 재고 7 (order → hub 차감)
        assertThat(order.status()).as("%s", order).isEqualTo(201);
        // 3. 본 수량(10)과 현재 수량(7)이 달라 409, details에 현재 수량. 조정이 반영되지 않아 재고 7 그대로
        //    (409가 아니라 200이면 주문 차감 3개가 덮여 재고가 8이 된다)
        assertThat(stale.status()).as("%s", stale).isEqualTo(409);
        assertThat(stale.errorCode()).isEqualTo("INVENTORY_QUANTITY_CONFLICT");
        assertThat(stale.errorFields()).containsExactly("quantity");
        assertThat(stale.error().path("details").get(0).path("reason").asText()).isEqualTo("현재 재고는 7개입니다.");
        // 4. 다시 조정 200, 재고 8. 이력은 입고·차감 뒤에 늘린 조정(ADJUST_INCREASE, 1개) 1건만 (3번의 조정 이력은 없음)
        assertThat(retry.status()).as("%s", retry).isEqualTo(200);
        assertThat(s.stockOfP()).isEqualTo(8);
        List<JsonNode> adjusts = new ArrayList<>();
        s.logsOf(inventoryId).forEach(log -> {
            if (log.path("changeType").asText().startsWith("ADJUST")) {
                adjusts.add(log);
            }
        });
        assertThat(adjusts).as("조정 이력").hasSize(1);
        assertThat(adjusts.get(0).path("changeType").asText()).isEqualTo("ADJUST_INCREASE");
        assertThat(adjusts.get(0).path("quantity").asInt()).isEqualTo(1);
    }
}
