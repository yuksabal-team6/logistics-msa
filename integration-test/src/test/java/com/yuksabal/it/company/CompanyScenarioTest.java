package com.yuksabal.it.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.yuksabal.it.support.Scenario.INITIAL_STOCK;
import static org.assertj.core.api.Assertions.assertThat;

// CMP: 업체·상품 시나리오 (노션 테스트 시나리오 창구)
class CompanyScenarioTest {

    private Scenario s;

    @BeforeEach
    void setUp() {
        s = new Scenario();
    }

    @Test
    @DisplayName("CMP-1. 비활성 업체 상품으로 주문: 422(공급 업체), 주문 저장 안 됨, 재고 그대로")
    void cmp1_비활성_업체_상품으로_주문() {
        Gateway.patch("/api/v1/companies/" + s.companyA + "/status", s.master, Map.of("active", false)).expect(200);

        Response order = s.order(s.u.token(), 2);

        assertThat(order.status()).as("%s", order).isEqualTo(422);
        // order는 공급 업체 → 요청 업체 → 상품 순서로 확인한다 (업체를 비활성화하면 상품도 비활성이지만 업체에서 먼저 걸린다)
        assertThat(order.error().path("details").get(0).path("field").asText()).isEqualTo("supplierCompanyId");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        JsonNode orders = Gateway.get("/api/v1/orders?size=100", s.u.token()).expect(200).data().path("items");
        assertThat(orders).as("u의 주문 목록").isEmpty();
    }

    @Test
    @DisplayName("CMP-2. 진행 중 주문이 있는 회원, 상품, 업체 삭제: 409 → 주문 취소 후 회원·업체 200, 상품은 재고가 남아 409")
    void cmp2_진행_중_주문이_있는_회원_상품_업체_삭제() {
        UUID orderId = s.order(s.u.token(), 2).expect(201).uuid("orderId");
        String user = "/api/v1/users/" + s.u.userId();
        String product = "/api/v1/products/" + s.productP;
        String companyB = "/api/v1/companies/" + s.companyB;
        // 상품·업체는 비활성 상태에서만 삭제할 수 있다 (활성이면 422라 남은 업무 확인까지 가지 않는다)
        Gateway.patch(product + "/status", s.master, Map.of("active", false)).expect(200);
        Gateway.patch(companyB + "/status", s.master, Map.of("active", false)).expect(200);

        // 1. 진행 중 주문이 있으면 회원·상품·업체(요청 업체 B) 모두 409
        Response userDelete = Gateway.delete(user, s.master);
        Response productDelete = Gateway.delete(product, s.master);
        Response companyDelete = Gateway.delete(companyB, s.master);

        assertThat(userDelete.status()).as("%s", userDelete).isEqualTo(409);
        assertThat(productDelete.status()).as("%s", productDelete).isEqualTo(409);
        assertThat(productDelete.errorFields()).as("%s", productDelete).containsExactlyInAnyOrder("inventory", "order");
        assertThat(companyDelete.status()).as("%s", companyDelete).isEqualTo(409);
        assertThat(companyDelete.errorFields()).as("%s", companyDelete).containsExactly("order");

        // 2. 주문을 취소하면 회원·업체는 삭제된다. 상품은 서울 허브 재고 10개가 남아 있어 409 (inventory)
        Gateway.post("/api/v1/orders/" + orderId + "/cancel", s.master, null).expect(200);

        Response userRetry = Gateway.delete(user, s.master);
        Response companyRetry = Gateway.delete(companyB, s.master);
        Response productRetry = Gateway.delete(product, s.master);

        assertThat(userRetry.status()).as("%s", userRetry).isEqualTo(200);
        assertThat(companyRetry.status()).as("%s", companyRetry).isEqualTo(200);
        assertThat(productRetry.status()).as("%s", productRetry).isEqualTo(409);
        assertThat(productRetry.errorFields()).as("%s", productRetry).containsExactly("inventory");
    }

    @Test
    @DisplayName("CMP-3. 비활성 상품으로 주문: 422(상품), 주문 저장 안 됨, 재고 그대로")
    void cmp3_비활성_상품으로_주문() {
        // 업체 A는 활성, 상품 P만 비활성
        Gateway.patch("/api/v1/products/" + s.productP + "/status", s.master, Map.of("active", false)).expect(200);

        Response order = s.order(s.u.token(), 2);

        assertThat(order.status()).as("%s", order).isEqualTo(422);
        assertThat(order.errorFields()).containsExactly("productId");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        JsonNode orders = Gateway.get("/api/v1/orders?size=100", s.u.token()).expect(200).data().path("items");
        assertThat(orders).as("u의 주문 목록").isEmpty();
    }

    @Test
    @DisplayName("CMP-4. 비활성 요청 업체로 주문: 422(요청 업체), 주문 저장 안 됨, 재고 그대로")
    void cmp4_비활성_요청_업체로_주문() {
        Gateway.patch("/api/v1/companies/" + s.companyB + "/status", s.master, Map.of("active", false)).expect(200);

        Response order = s.order(s.u.token(), 2);

        assertThat(order.status()).as("%s", order).isEqualTo(422);
        // 공급 업체 A·상품 P는 활성이라 요청 업체 확인에서 걸린다
        assertThat(order.errorFields()).containsExactly("requestCompanyId");
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        JsonNode orders = Gateway.get("/api/v1/orders?size=100", s.u.token()).expect(200).data().path("items");
        assertThat(orders).as("u의 주문 목록").isEmpty();
    }
}
