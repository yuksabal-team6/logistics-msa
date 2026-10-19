package com.yuksabal.it.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.yuksabal.it.support.Scenario.INITIAL_STOCK;
import static org.assertj.core.api.Assertions.assertThat;

// HUB: 재고 시나리오 (노션 테스트 시나리오 창구)
// 테스트 본문은 노션 본문과 같은 순서로 쓴다: 사전 조건 → 이렇게 하면 → 이렇게 돼야 한다
// 시나리오마다 공통 준비(업체 A·B, 상품 P, 재고 10개, 회원 u·a·b)를 새로 만든다 (Scenario 참고)
//
// hub는 입고·재고 조회 때 company 내부 API(#1 상품)로 상품의 활성 여부·업체·관리 허브를 확인한다
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
}
