package com.yuksabal.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.it.support.Gateway.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// 시나리오 공통 준비 (노션 ORD-1의 "공통 준비")와 시나리오에서 자주 쓰는 요청 모음
//
// 만들어지는 데이터 (new Scenario() 할 때마다 새로 만든다)
// - 공급 업체 A (서울 허브), 요청 업체 B (부산 허브)
// - A의 상품 P, 서울 허브에 P 재고 10개 (new Scenario(false)면 재고 없음)
// - 회원 u (USER), A 관리자 a (COMPANY, A 소속), B 관리자 b (COMPANY, B 소속)
//
// 개발 DB를 함께 쓰므로 이름에 [IT] 접두사와 임의 문자열을 붙여 다른 데이터와 겹치지 않게 한다
// 요청 도우미는 준비 단계용이라 실패하면 바로 멈춘다 (.expect). 확인할 요청은 테스트에서 Gateway로 직접 보내 결과를 검사한다
public final class Scenario {

    // hub-service 초기 데이터의 고정 ID
    public static final UUID SEOUL_HUB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID BUSAN_HUB = UUID.fromString("00000000-0000-0000-0000-000000000004");
    public static final UUID GYEONGGI_NORTH_HUB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final int INITIAL_STOCK = 10;

    // 테스트 회원 비밀번호 (회원가입 규칙: 8~15자, 대·소문자·숫자·특수문자)
    private static final String PASSWORD = "ItUser12!";

    public final String master = Accounts.masterToken();
    public final String suffix = randomSuffix();

    public final Member u;
    public final Member a;
    public final Member b;
    public final UUID companyA;
    public final UUID companyB;
    public final UUID productP;

    public Scenario() {
        this(true);
    }

    // withStock = false면 서울 허브에 입고하지 않는다 (재고가 없는 업체로 시작하는 시나리오용, 예: 관리 허브 변경)
    public Scenario(boolean withStock) {
        u = register("u");
        a = companyManager("a");
        b = companyManager("b");

        companyA = createCompany("공급 업체 A", SEOUL_HUB, a);
        companyB = createCompany("요청 업체 B", BUSAN_HUB, b);
        assignCompany(a, companyA);
        assignCompany(b, companyB);

        productP = Gateway.post("/api/v1/companies/" + companyA + "/products", master,
                        Map.of("productName", "[IT] 상품 P " + suffix, "description", "통합 테스트", "unitPrice", 5000))
                .expect(201).uuid("productId");
        if (withStock) {
            inbound(SEOUL_HUB, INITIAL_STOCK);
        }
    }

    // P 입고 POST /api/v1/inventories/inbound (MASTER). 입고는 상품 업체의 관리 허브에만 할 수 있다 (#175)
    public void inbound(UUID hubId, int quantity) {
        Gateway.post("/api/v1/inventories/inbound", master,
                        Map.of("hubId", hubId, "productId", productP, "quantity", quantity))
                .expect(200);
    }

    // ---- 주문 ----

    // B가 A의 상품 P를 주문하는 기본 요청 본문. 값 하나만 바꾸려면 new HashMap<>(orderBody(n))으로 복사해서 쓴다
    public Map<String, Object> orderBody(int quantity) {
        return Map.of(
                "requestCompanyId", companyB,
                "supplierCompanyId", companyA,
                "productId", productP,
                "quantity", quantity,
                "recipientName", "[IT] 수령인",
                "deliveryAddress", "부산광역시 중구 중앙대로 1");
    }

    // 주문 생성 POST /api/v1/orders (Idempotency-Key는 매번 새로). 결과를 검사하려고 Response를 그대로 돌려준다
    public Response order(String token, int quantity) {
        return Gateway.postWithIdempotencyKey("/api/v1/orders", token, orderBody(quantity), UUID.randomUUID());
    }

    // 주문 취소 POST /api/v1/orders/{orderId}/cancel. 권한에 따라 결과가 달라서 Response를 그대로 돌려준다
    public Response cancel(UUID orderId, String token) {
        return Gateway.post("/api/v1/orders/" + orderId + "/cancel", token, null);
    }

    // 주문 단건 조회 (MASTER) GET /api/v1/orders/{orderId} → data
    public JsonNode orderDetail(UUID orderId) {
        return Gateway.get("/api/v1/orders/" + orderId, master).expect(200).data();
    }

    // 그 토큰 주인의 주문 목록 GET /api/v1/orders → data.items (MASTER가 아니면 본인 주문만 나온다)
    public JsonNode ordersOf(String token) {
        return Gateway.get("/api/v1/orders?size=100", token).expect(200).data().path("items");
    }

    // ---- 재고·배송 ----

    // P의 재고 (MASTER) GET /api/v1/inventories/products/{P} → data (inventoryId, hubId, quantity)
    // 상품 재고는 업체 관리 허브 한 곳에만 있어 단건으로 응답한다
    public JsonNode inventoryOfP() {
        return Gateway.get("/api/v1/inventories/products/" + productP, master).expect(200).data();
    }

    // P의 재고 수량
    public int stockOfP() {
        return inventoryOfP().path("quantity").asInt();
    }

    // 재고 변경 이력 (MASTER) GET /api/v1/inventories/logs?inventoryId= → data.items (changeType, quantity, orderId, ...)
    public JsonNode logsOf(String inventoryId) {
        return Gateway.get("/api/v1/inventories/logs?size=100&inventoryId=" + inventoryId, master).expect(200)
                .data().path("items");
    }

    // 재고 변경 이력의 종류 목록 → changeType (INBOUND, DECREASE, RESTORE, ADJUST_INCREASE, ADJUST_DECREASE)
    // 건수를 셀 때는 Collections.frequency(changeTypesOf(id), "DECREASE")
    public List<String> changeTypesOf(String inventoryId) {
        List<String> types = new ArrayList<>();
        logsOf(inventoryId).forEach(log -> types.add(log.path("changeType").asText()));
        return types;
    }

    // 배송 단건 조회 (MASTER) GET /api/v1/deliveries/{deliveryId} → data
    public JsonNode delivery(UUID deliveryId) {
        return Gateway.get("/api/v1/deliveries/" + deliveryId, master).expect(200).data();
    }

    // 서울 → 부산 배송을 업체 배송 중(DELIVERING)까지 진행한다 (MASTER는 담당자가 아니어도 상태를 바꿀 수 있다)
    // 1. 허브 → 허브 경로 기록: READY → IN_TRANSIT → ARRIVED (도착 시 실제 거리 필수)
    // 2. 허브 → 업체 배송: ARRIVED_AT_HUB → DELIVERING
    public void startDelivering(UUID deliveryId) {
        String delivery = "/api/v1/deliveries/" + deliveryId;
        String routeLogId = Gateway.get(delivery + "/routes", master).expect(200).data().get(0).path("routeLogId").asText();
        String route = delivery + "/routes/" + routeLogId + "/status";

        Gateway.patch(route, master, Map.of("routeStatus", "IN_TRANSIT")).expect(200);
        Gateway.patch(route, master, Map.of("routeStatus", "ARRIVED", "actualDistanceKm", 325.5)).expect(200);
        Gateway.patch(delivery + "/status", master, Map.of("deliveryStatus", "DELIVERING")).expect(200);
    }

    // 배송 완료 요청 PATCH /api/v1/deliveries/{id}/status COMPLETED (DELIVERING에서만). 동시 요청 시나리오에서 결과를 보려고 Response를 돌려준다
    // 배송이 COMPLETED가 되면 delivery가 order에 알려(내부 API #10) 주문도 COMPLETED가 된다
    public Response requestDeliveryComplete(UUID deliveryId) {
        return Gateway.patch("/api/v1/deliveries/" + deliveryId + "/status", master, Map.of("deliveryStatus", "COMPLETED"));
    }

    // 서울 → 부산 배송을 끝까지 진행한다 (DELIVERING까지 진행 → COMPLETED)
    public void completeDelivery(UUID deliveryId) {
        startDelivering(deliveryId);
        requestDeliveryComplete(deliveryId).expect(200);
    }

    // ---- 상태 변경 (MASTER) ----

    // 업체 비활성화 PATCH /api/v1/companies/{id}/status. 소속 상품도 함께 비활성이 된다
    public void deactivateCompany(UUID companyId) {
        Gateway.patch("/api/v1/companies/" + companyId + "/status", master, Map.of("active", false)).expect(200);
    }

    // 상품 P 비활성화 PATCH /api/v1/products/{P}/status (업체 A는 활성 그대로)
    public void deactivateProduct() {
        Gateway.patch("/api/v1/products/" + productP + "/status", master, Map.of("active", false)).expect(200);
    }

    // 회원 비활성화(정지) PATCH /api/v1/users/{id}/status. 이미 받은 토큰은 만료 전까지 게이트웨이를 통과한다
    public void deactivateUser(Member member) {
        Gateway.patch("/api/v1/users/" + member.userId() + "/status", master, Map.of("active", false)).expect(200);
    }

    // ---- 준비 단계 ----

    // 회원가입은 항상 USER로 만들어진다
    private Member register(String name) {
        String username = name + suffix;
        UUID userId = Gateway.post("/api/v1/auth/register", null, Map.of(
                        "username", username,
                        "password", PASSWORD,
                        "slackId", "it-" + username,
                        "email", username + "@it.test"))
                .expect(201).uuid("userId");
        return new Member(userId, username);
    }

    // USER 회원을 새로 만든다 (u 말고 주문자가 더 필요한 시나리오용, 예: 두 회원이 동시에 주문). name은 회원끼리 구분하는 한 글자 (예: "v")
    public Member user(String name) {
        return register(name);
    }

    // 아직 업체를 관리하지 않는 COMPANY 회원을 새로 만든다 (업체 생성·관리자 교체 시나리오용)
    // 업체 관리자는 USER로 가입한 뒤 MASTER가 역할을 COMPANY로 바꾼다. name은 회원끼리 구분하는 한 글자 (예: "n")
    public Member companyManager(String name) {
        Member member = register(name);
        Gateway.patch("/api/v1/users/" + member.userId() + "/role", master, Map.of("role", "COMPANY")).expect(200);
        return member;
    }

    // 회원의 소속 업체 지정 PATCH /api/v1/users/{id} (MASTER)
    // 업체를 만든 직후에는 관리자의 소속(company_id)이 비어 있어 이걸로 지정한다. 관리자 교체 전 새 관리자를 그 업체 소속으로 만들 때도 쓴다
    public void assignCompany(Member member, UUID companyId) {
        Gateway.patch("/api/v1/users/" + member.userId(), master, Map.of("companyId", companyId)).expect(200);
    }

    private UUID createCompany(String name, UUID hubId, Member owner) {
        return Gateway.post("/api/v1/companies", master, Map.of(
                        "companyName", "[IT] " + name + " " + suffix,
                        "hubId", hubId,
                        "ownerId", owner.userId(),
                        "address", "통합 테스트 주소"))
                .expect(201).uuid("companyId");
    }

    // 아이디 규칙(소문자·숫자 4~10자)에 맞게 이름 1자 + 6자리
    private static String randomSuffix() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }

    // 시나리오 속 회원. token()을 부를 때마다 로그인한다 (회원을 정지하기 전에 미리 받아 둘 수도 있다)
    public record Member(UUID userId, String username) {

        public String token() {
            return Accounts.login(username, PASSWORD);
        }
    }
}
