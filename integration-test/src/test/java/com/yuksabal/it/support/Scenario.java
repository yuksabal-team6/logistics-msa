package com.yuksabal.it.support;

import com.yuksabal.it.support.Gateway.Response;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// 시나리오 공통 준비 (노션 ORD 시나리오의 "공통 준비")
// - 공급 업체 A (서울 허브), 요청 업체 B (부산 허브)
// - A의 상품 P, 서울 허브에 P 재고 10개
// - 회원 u (USER), A 관리자 a (COMPANY, A 소속), B 관리자 b (COMPANY, B 소속)
// 개발 DB를 함께 쓰므로 매번 새로 만들고, 이름에 [IT] 접두사를 붙여 구분한다
public final class Scenario {

    // hub-service 초기 데이터의 고정 ID
    public static final UUID SEOUL_HUB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID BUSAN_HUB = UUID.fromString("00000000-0000-0000-0000-000000000004");
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
        Gateway.post("/api/v1/inventories/inbound", master,
                        Map.of("hubId", SEOUL_HUB, "productId", productP, "quantity", INITIAL_STOCK))
                .expect(200);
    }

    // ---- 시나리오에서 쓰는 요청 ----

    // u가 A의 P를 B로 주문하는 기본 요청 본문
    public Map<String, Object> orderBody(int quantity) {
        return Map.of(
                "requestCompanyId", companyB,
                "supplierCompanyId", companyA,
                "productId", productP,
                "quantity", quantity,
                "recipientName", "[IT] 수령인",
                "deliveryAddress", "부산광역시 중구 중앙대로 1");
    }

    public Response order(String token, int quantity) {
        return Gateway.postWithIdempotencyKey("/api/v1/orders", token, orderBody(quantity), UUID.randomUUID());
    }

    public int stockOfP() {
        return Gateway.get("/api/v1/inventories/products/" + productP, master).expect(200).data().path("quantity").asInt();
    }

    // ---- 준비 단계 ----

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

    private Member companyManager(String name) {
        Member member = register(name);
        Gateway.patch("/api/v1/users/" + member.userId() + "/role", master, Map.of("role", "COMPANY")).expect(200);
        return member;
    }

    // 업체를 만든 직후에는 관리자의 소속(company_id)이 비어 있어 MASTER가 회원 수정으로 지정한다
    private void assignCompany(Member member, UUID companyId) {
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

    public record Member(UUID userId, String username) {

        public String token() {
            return Accounts.login(username, PASSWORD);
        }
    }
}
