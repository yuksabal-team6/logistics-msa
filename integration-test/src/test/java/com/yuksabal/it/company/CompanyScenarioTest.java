package com.yuksabal.it.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway;
import com.yuksabal.it.support.Gateway.Response;
import com.yuksabal.it.support.Scenario;
import com.yuksabal.it.support.Scenario.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.util.Map;
import java.util.UUID;

import static com.yuksabal.it.support.Scenario.INITIAL_STOCK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

// CMP: 업체·상품 시나리오 (노션 테스트 시나리오 창구)
// 테스트 본문은 노션 본문과 같은 순서로 쓴다: 사전 조건 → 이렇게 하면 → 이렇게 돼야 한다
// 시나리오마다 공통 준비(업체 A·B, 상품 P, 재고 10개, 회원 u·a·b)를 새로 만든다 (Scenario 참고)
//
// 주문 시 order의 active 확인 순서: 공급 업체 → 요청 업체 → 상품 (먼저 걸린 곳이 오류 field로 나간다)
class CompanyScenarioTest {

    private Scenario s;

    @BeforeEach
    void setUp() {
        s = new Scenario();
    }

    @Test
    @DisplayName("CMP-1. 비활성 업체 상품으로 주문: 422(공급 업체), 주문 저장 안 됨, 재고 그대로")
    void cmp1_비활성_업체_상품으로_주문() {
        // 사전 조건: 공급 업체 A 비활성화 (상품 P도 함께 비활성이 된다)
        s.deactivateCompany(s.companyA);

        // 이렇게 하면: u가 P를 주문
        Response order = s.order(s.u.token(), 2);

        // 이렇게 돼야 한다
        // 1. 422. 상품도 비활성이지만 공급 업체 확인에서 먼저 걸린다
        assertThat(order.status()).as("%s", order).isEqualTo(422);
        assertThat(order.errorFields()).containsExactly("supplierCompanyId");
        // 2. 재고 그대로, 주문 저장 안 됨
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        assertThat(s.ordersOf(s.u.token())).as("u의 주문 목록").isEmpty();
    }

    @Test
    @DisplayName("CMP-2. 진행 중 주문이 있는 회원, 상품, 업체 삭제: 409 → 주문 취소 후 회원·업체 200, 상품은 재고가 남아 409")
    void cmp2_진행_중_주문이_있는_회원_상품_업체_삭제() {
        // 사전 조건
        // - u의 CONFIRMED 주문 O
        // - 상품 P·요청 업체 B 비활성화 (상품·업체는 비활성일 때만 삭제할 수 있다. 활성이면 422에서 끝나 남은 업무 확인까지 가지 않는다)
        UUID orderId = s.order(s.u.token(), 2).expect(201).uuid("orderId");
        String user = "/api/v1/users/" + s.u.userId();
        String product = "/api/v1/products/" + s.productP;
        String companyB = "/api/v1/companies/" + s.companyB;
        s.deactivateProduct();
        s.deactivateCompany(s.companyB);

        // 이렇게 하면 (1): O가 진행 중일 때 회원·상품·업체 삭제
        Response userDelete = Gateway.delete(user, s.master);
        Response productDelete = Gateway.delete(product, s.master);
        Response companyDelete = Gateway.delete(companyB, s.master);

        // 이렇게 돼야 한다 (1): 모두 409
        // - user·company는 order의 진행 중 주문 확인(ordererId / productId / companyId)으로 막는다
        // - 상품은 재고(hub)와 진행 중 주문(order)이 둘 다 남아 있어 details에 둘 다 나온다
        //   (삭제 확인이 첫 결과에서 멈추지 않고 모두 모으는지도 함께 확인된다)
        assertThat(userDelete.status()).as("%s", userDelete).isEqualTo(409);
        assertThat(productDelete.status()).as("%s", productDelete).isEqualTo(409);
        assertThat(productDelete.errorFields()).as("%s", productDelete).containsExactlyInAnyOrder("inventory", "order");
        assertThat(companyDelete.status()).as("%s", companyDelete).isEqualTo(409);
        assertThat(companyDelete.errorFields()).as("%s", companyDelete).containsExactly("order");

        // 이렇게 하면 (2): O를 취소한 뒤 다시 삭제
        s.cancel(orderId, s.master).expect(200);
        Response userRetry = Gateway.delete(user, s.master);
        Response companyRetry = Gateway.delete(companyB, s.master);
        Response productRetry = Gateway.delete(product, s.master);

        // 이렇게 돼야 한다 (2): 회원·업체 200, 상품은 서울 허브 재고 10개가 남아 있어 409 (inventory)
        assertThat(userRetry.status()).as("%s", userRetry).isEqualTo(200);
        assertThat(companyRetry.status()).as("%s", companyRetry).isEqualTo(200);
        assertThat(productRetry.status()).as("%s", productRetry).isEqualTo(409);
        assertThat(productRetry.errorFields()).as("%s", productRetry).containsExactly("inventory");
    }

    @Test
    @DisplayName("CMP-3. 비활성 상품으로 주문: 422(상품), 주문 저장 안 됨, 재고 그대로")
    void cmp3_비활성_상품으로_주문() {
        // 사전 조건: 상품 P만 비활성화 (업체 A는 활성)
        s.deactivateProduct();

        // 이렇게 하면: u가 P를 주문
        Response order = s.order(s.u.token(), 2);

        // 이렇게 돼야 한다
        // 1. 422. 공급 업체·요청 업체는 통과하고 상품 확인에서 걸린다
        assertThat(order.status()).as("%s", order).isEqualTo(422);
        assertThat(order.errorFields()).containsExactly("productId");
        // 2. 재고 그대로, 주문 저장 안 됨
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        assertThat(s.ordersOf(s.u.token())).as("u의 주문 목록").isEmpty();
    }

    @Test
    @DisplayName("CMP-4. 비활성 요청 업체로 주문: 422(요청 업체), 주문 저장 안 됨, 재고 그대로")
    void cmp4_비활성_요청_업체로_주문() {
        // 사전 조건: 요청 업체 B 비활성화
        s.deactivateCompany(s.companyB);

        // 이렇게 하면: u가 B 앞으로 P를 주문
        Response order = s.order(s.u.token(), 2);

        // 이렇게 돼야 한다
        // 1. 422. 공급 업체 A는 활성이라 통과하고 요청 업체 확인에서 걸린다
        assertThat(order.status()).as("%s", order).isEqualTo(422);
        assertThat(order.errorFields()).containsExactly("requestCompanyId");
        // 2. 재고 그대로, 주문 저장 안 됨
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
        assertThat(s.ordersOf(s.u.token())).as("u의 주문 목록").isEmpty();
    }

    @Test
    @DisplayName("CMP-5. 없는 허브로 업체 생성: hub의 404가 그대로 전달, 업체 생성 안 됨")
    void cmp5_없는_허브로_업체_생성() {
        // 사전 조건: 아직 업체를 관리하지 않는 COMPANY 회원 n
        // (a·b는 이미 업체를 관리해서 관리자 중복 409가 허브 확인보다 먼저 걸린다)
        Member n = s.companyManager("n");
        String companyName = "[IT] 없는 허브 업체 " + s.suffix;

        // 이렇게 하면: MASTER가 존재하지 않는 허브로 업체 생성
        Response create = Gateway.post("/api/v1/companies", s.master, Map.of(
                "companyName", companyName,
                "hubId", UUID.randomUUID(),
                "ownerId", n.userId(),
                "address", "통합 테스트 주소"));

        // 이렇게 돼야 한다
        // 1. company가 hub 내부 API(#7)로 허브를 확인하고, hub의 404를 코드 그대로 전달
        assertThat(create.status()).as("%s", create).isEqualTo(404);
        assertThat(create.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        // 2. 업체가 만들어지지 않음
        JsonNode found = Gateway.get("/api/v1/companies?companyName=" + URLEncoder.encode(companyName, UTF_8), s.master)
                .expect(200).data();
        assertThat(found.path("totalElements").asInt()).as("같은 이름 업체 수").isZero();
    }

    @Test
    @DisplayName("CMP-6. COMPANY가 아니거나 비활성인 회원을 관리자로 업체 생성: user의 실제 역할·활성 여부로 422")
    void cmp6_COMPANY가_아니거나_비활성인_회원을_관리자로_업체_생성() {
        // 사전 조건: USER 회원 u, 정지된 COMPANY 회원 d (둘 다 업체를 관리하지 않음)
        Member d = s.companyManager("d");
        s.deactivateUser(d);

        // 이렇게 하면: MASTER가 u, d를 각각 관리자로 업체 생성
        Response byUser = Gateway.post("/api/v1/companies", s.master, companyBody(s.u, "u"));
        Response byInactive = Gateway.post("/api/v1/companies", s.master, companyBody(d, "d"));

        // 이렇게 돼야 한다: company가 user 내부 API(#8)의 실제 role·active로 판단해 둘 다 422 (field: ownerId)
        // 1. u: 역할이 USER
        assertThat(byUser.status()).as("%s", byUser).isEqualTo(422);
        assertThat(byUser.errorMessage()).isEqualTo("COMPANY 역할 회원만 업체 관리자로 지정할 수 있습니다.");
        assertThat(byUser.errorFields()).containsExactly("ownerId");
        // 2. d: COMPANY지만 정지됨
        assertThat(byInactive.status()).as("%s", byInactive).isEqualTo(422);
        assertThat(byInactive.errorMessage()).isEqualTo("비활성화된 회원은 업체 관리자로 지정할 수 없습니다.");
        assertThat(byInactive.errorFields()).containsExactly("ownerId");
    }

    @Test
    @DisplayName("CMP-7. 업체 관리자 교체 (소속 확인): 다른 업체 소속이면 422, 이 업체 소속이면 200 + 권한 이동")
    void cmp7_업체_관리자_교체_소속_확인() {
        // 사전 조건: 업체를 관리하지 않는 COMPANY 회원 두 명 - B 소속 o, A 소속 m
        // (b는 B 소속이지만 이미 B의 관리자라, 관리자 중복 409가 소속 확인(422)보다 먼저 걸린다 → 소속 확인 경로를 보려고 o를 따로 만든다)
        Member o = s.companyManager("o");
        s.assignCompany(o, s.companyB);
        Member m = s.companyManager("m");
        s.assignCompany(m, s.companyA);
        String companyA = "/api/v1/companies/" + s.companyA;
        String productP = "/api/v1/products/" + s.productP;

        // 이렇게 하면 (1): MASTER가 A의 관리자를 o(B 소속)로 교체
        Response toOtherCompany = Gateway.patch(companyA, s.master, Map.of("ownerId", o.userId()));

        // 이렇게 돼야 한다 (1): company가 user 내부 API(#8)의 실제 companyId(B)로 판단해 422
        assertThat(toOtherCompany.status()).as("%s", toOtherCompany).isEqualTo(422);
        assertThat(toOtherCompany.errorMessage()).isEqualTo("이 업체 소속 회원만 업체 관리자로 지정할 수 있습니다.");
        assertThat(toOtherCompany.errorFields()).containsExactly("ownerId");

        // 이렇게 하면 (2): MASTER가 A의 관리자를 m(A 소속)으로 교체한 뒤, a와 m이 각각 P를 수정
        Response toSameCompany = Gateway.patch(companyA, s.master, Map.of("ownerId", m.userId()));
        Response byOldOwner = Gateway.patch(productP, s.a.token(), Map.of("unitPrice", 6000));
        Response byNewOwner = Gateway.patch(productP, m.token(), Map.of("unitPrice", 7000));

        // 이렇게 돼야 한다 (2): 교체 200, 상품 관리 권한이 a에서 m으로 넘어감
        assertThat(toSameCompany.status()).as("%s", toSameCompany).isEqualTo(200);
        assertThat(toSameCompany.data().path("ownerId").asText()).isEqualTo(m.userId().toString());
        assertThat(byOldOwner.status()).as("%s", byOldOwner).isEqualTo(403);
        assertThat(byOldOwner.errorCode()).isEqualTo("COMPANY_ACCESS_DENIED");
        assertThat(byNewOwner.status()).as("%s", byNewOwner).isEqualTo(200);
    }

    @Test
    @DisplayName("CMP-8. 남은 업무 없는 업체의 관리 허브 변경 후 주문: 200, 새 주문의 출발 허브가 새 허브")
    void cmp8_남은_업무_없는_업체의_관리_허브_변경_후_주문() {
        // 사전 조건: 재고가 없는 공통 준비 (A는 서울 허브, P 재고 없음, 진행 중 주문 없음)
        Scenario noStock = new Scenario(false);

        // 이렇게 하면
        // 1. MASTER가 A의 관리 허브를 서울 → 경기 북부로 변경
        Response change = Gateway.patch("/api/v1/companies/" + noStock.companyA, noStock.master,
                Map.of("hubId", Scenario.GYEONGGI_NORTH_HUB));
        // 2. 새 관리 허브(경기 북부)에 P를 10개 입고하고, u가 P를 2개 주문
        noStock.inbound(Scenario.GYEONGGI_NORTH_HUB, INITIAL_STOCK);
        Response order = noStock.order(noStock.u.token(), 2);

        // 이렇게 돼야 한다
        // 1. company: 재고·진행 중 주문이 없어 허브 변경 200
        assertThat(change.status()).as("%s", change).isEqualTo(200);
        assertThat(change.data().path("hubId").asText()).isEqualTo(Scenario.GYEONGGI_NORTH_HUB.toString());
        // 2. order: 공급 업체의 새 허브에서 출고 → 201, 경기 북부 재고 10 → 8
        assertThat(order.status()).as("%s", order).isEqualTo(201);
        assertThat(noStock.stockOfP()).isEqualTo(INITIAL_STOCK - 2);
        // 3. delivery: 출발 허브가 경기 북부 (order가 company #2로 받은 공급 업체의 현재 허브)
        assertThat(noStock.delivery(order.uuid("deliveryId")).path("departureHubId").asText())
                .isEqualTo(Scenario.GYEONGGI_NORTH_HUB.toString());
    }

    @Test
    @DisplayName("CMP-9. 재고가 남은 업체의 관리 허브 변경: 409(inventory), 허브 그대로")
    void cmp9_재고가_남은_업체의_관리_허브_변경() {
        // 사전 조건: 공통 준비 (서울 허브에 P 재고 10개)

        // 이렇게 하면: MASTER가 A의 관리 허브를 서울 → 경기 북부로 변경
        Response change = Gateway.patch("/api/v1/companies/" + s.companyA, s.master,
                Map.of("hubId", Scenario.GYEONGGI_NORTH_HUB));

        // 이렇게 돼야 한다
        // 1. company가 hub 재고 확인(GET /internal/inventories/stock?companyId=)으로 남은 재고를 보고 409
        //    (바꾸면 서울 재고가 주문으로 빠질 수 없게 갇힌다, #161)
        assertThat(change.status()).as("%s", change).isEqualTo(409);
        assertThat(change.errorCode()).isEqualTo("HAS_ACTIVE_WORK");
        assertThat(change.errorMessage()).isEqualTo("재고나 진행 중인 주문이 있어 업체의 관리 허브를 바꿀 수 없습니다.");
        assertThat(change.errorFields()).containsExactly("inventory");
        // 2. A의 관리 허브는 서울 그대로
        assertThat(Gateway.get("/api/v1/companies/" + s.companyA, s.master).expect(200).data().path("hubId").asText())
                .isEqualTo(Scenario.SEOUL_HUB.toString());
    }

    @Test
    @DisplayName("CMP-10. 재고는 0이지만 진행 중 주문이 있는 업체의 관리 허브 변경: 409(order만), 허브 그대로")
    void cmp10_재고는_0이지만_진행_중_주문이_있는_업체의_관리_허브_변경() {
        // 사전 조건: u가 P를 10개 모두 주문 → 서울 재고 0, 주문 O는 CONFIRMED (진행 중)
        s.order(s.u.token(), INITIAL_STOCK).expect(201);
        assertThat(s.stockOfP()).as("사전 조건: 서울 재고").isZero();

        // 이렇게 하면: MASTER가 A의 관리 허브를 서울 → 경기 북부로 변경
        Response change = Gateway.patch("/api/v1/companies/" + s.companyA, s.master,
                Map.of("hubId", Scenario.GYEONGGI_NORTH_HUB));

        // 이렇게 돼야 한다
        // 1. 409, details는 order만 (수량 0인 재고는 이력이라 inventory로 막지 않는다)
        //    재고만 확인하면 놓치는 경우: O가 취소되면 복원 재고가 차감했던 서울 재고 행으로 돌아가 갇힌다 (#161)
        assertThat(change.status()).as("%s", change).isEqualTo(409);
        assertThat(change.errorCode()).isEqualTo("HAS_ACTIVE_WORK");
        assertThat(change.errorFields()).containsExactly("order");
        // 2. A의 관리 허브는 서울 그대로
        assertThat(Gateway.get("/api/v1/companies/" + s.companyA, s.master).expect(200).data().path("hubId").asText())
                .isEqualTo(Scenario.SEOUL_HUB.toString());
    }

    @Test
    @DisplayName("CMP-11. 업체 비활성화 후 그 업체 상품 입고: 상품도 비활성, 입고 422, 재고 그대로")
    void cmp11_업체_비활성화_후_그_업체_상품_입고() {
        // 사전 조건: 공급 업체 A 비활성화
        s.deactivateCompany(s.companyA);

        // 이렇게 하면: MASTER가 서울 허브에 P를 5개 입고
        Response inbound = Gateway.post("/api/v1/inventories/inbound", s.master,
                Map.of("hubId", Scenario.SEOUL_HUB, "productId", s.productP, "quantity", 5));

        // 이렇게 돼야 한다
        // 1. company: 업체를 비활성화하면 소속 상품도 비활성
        assertThat(Gateway.get("/api/v1/products/" + s.productP, s.master).expect(200).data().path("active").asBoolean())
                .as("P의 활성 여부").isFalse();
        // 2. hub: company 내부 API(#1)의 active=false를 보고 입고를 막는다
        assertThat(inbound.status()).as("%s", inbound).isEqualTo(422);
        // 3. hub: 재고 그대로
        assertThat(s.stockOfP()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    @DisplayName("CMP-12. 진행 중 주문이 있는 공급 업체 비활성화 후 배송 완료: 진행 중 주문은 COMPLETED, 새 주문은 422")
    void cmp12_진행_중_주문이_있는_공급_업체_비활성화_후_배송_완료() {
        // 사전 조건: u의 CONFIRMED 주문 O가 있는 상태에서 공급 업체 A 비활성화
        Response order = s.order(s.u.token(), 2).expect(201);
        s.deactivateCompany(s.companyA);

        // 이렇게 하면 (1): O의 배송을 끝까지 진행 (각 단계 200이 아니면 바로 실패)
        s.completeDelivery(order.uuid("deliveryId"));

        // 이렇게 돼야 한다 (1): 비활성은 새 거래만 막고 진행 중인 주문·배송은 끝까지 간다
        assertThat(s.delivery(order.uuid("deliveryId")).path("deliveryStatus").asText()).isEqualTo("COMPLETED");
        assertThat(s.orderDetail(order.uuid("orderId")).path("status").asText()).isEqualTo("COMPLETED");

        // 이렇게 하면 (2): u가 P를 새로 주문
        Response newOrder = s.order(s.u.token(), 2);

        // 이렇게 돼야 한다 (2): 새 주문은 공급 업체 확인에서 422
        assertThat(newOrder.status()).as("%s", newOrder).isEqualTo(422);
        assertThat(newOrder.errorFields()).containsExactly("supplierCompanyId");
    }

    @Test
    @DisplayName("CMP-13. 재고가 남은 상품 삭제: 409(inventory), 상품 그대로")
    void cmp13_재고가_남은_상품_삭제() {
        // 사전 조건: 공통 준비 (서울 허브에 P 재고 10개, 진행 중 주문 없음) + P 비활성화 (상품은 비활성일 때만 삭제 가능)
        s.deactivateProduct();

        // 이렇게 하면: MASTER가 P 삭제
        Response delete = Gateway.delete("/api/v1/products/" + s.productP, s.master);

        // 이렇게 돼야 한다
        // 1. company가 hub 재고 확인(GET /internal/inventories/stock?productId=)으로 남은 재고를 보고 409
        //    (재고는 허브에 실제로 있는 물건이라 기록을 지우면 안 된다)
        assertThat(delete.status()).as("%s", delete).isEqualTo(409);
        assertThat(delete.errorCode()).isEqualTo("HAS_ACTIVE_WORK");
        assertThat(delete.errorMessage()).isEqualTo("재고나 진행 중인 주문이 있어 상품을 삭제할 수 없습니다.");
        assertThat(delete.errorFields()).containsExactly("inventory");
        // 2. 상품은 삭제되지 않음
        Gateway.get("/api/v1/products/" + s.productP, s.master).expect(200);
    }

    @Test
    @DisplayName("CMP-14. 수량 0 재고만 있는 상품 삭제: 200 (재고 0은 이력, 완료된 주문은 진행 중 아님)")
    void cmp14_수량_0_재고만_있는_상품_삭제() {
        // 사전 조건: u가 P를 10개 모두 주문하고 배송 완료까지 진행 → 서울 재고 0, 주문 COMPLETED
        Response order = s.order(s.u.token(), INITIAL_STOCK).expect(201);
        s.completeDelivery(order.uuid("deliveryId"));
        assertThat(s.stockOfP()).as("사전 조건: 서울 재고").isZero();
        assertThat(s.orderDetail(order.uuid("orderId")).path("status").asText()).as("사전 조건: 주문 상태").isEqualTo("COMPLETED");
        s.deactivateProduct();

        // 이렇게 하면: MASTER가 P 삭제
        Response delete = Gateway.delete("/api/v1/products/" + s.productP, s.master);

        // 이렇게 돼야 한다
        // 1. hub: 수량 0인 재고는 이력이라 hasStock=false, order: COMPLETED는 진행 중 주문이 아님 → 200
        assertThat(delete.status()).as("%s", delete).isEqualTo(200);
        // 2. 상품 조회 404 (Soft Delete)
        Response found = Gateway.get("/api/v1/products/" + s.productP, s.master);
        assertThat(found.status()).as("%s", found).isEqualTo(404);
    }

    // 업체 생성 요청 본문 (서울 허브). 이름에 suffix를 붙여 다른 테스트 데이터와 구분한다
    private Map<String, Object> companyBody(Member owner, String label) {
        return Map.of(
                "companyName", "[IT] 관리자 " + label + " 업체 " + s.suffix,
                "hubId", Scenario.SEOUL_HUB,
                "ownerId", owner.userId(),
                "address", "통합 테스트 주소");
    }
}
