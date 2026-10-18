package com.yuksabal.gateway.global.filter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuksabal.gateway.support.TestJwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// application.yml의 실제 역할 규칙(auth.role-rules)을 그대로 쓴다 (테스트 전용 규칙으로 덮어쓰지 않음)
// 모든 하위 서비스 → 같은 stub. 규칙에 막히면 stub이 호출되지 않아야 한다
// yml에 규칙을 추가·수정하면 이 테스트의 표(SERVICE_RULES)도 같이 고친다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
class GatewayRoleRulesIntegrationTest {

    private static final String SECRET = TestJwt.randomBase64Secret(32);
    private static final HttpServer STUB = startStub();
    private static final AtomicInteger STUB_HITS = new AtomicInteger();
    private static final String[] NON_MASTER = {"USER", "COMPANY", "DELIVERY"};
    private static final List<String> ALL_ROLES = List.of("MASTER", "USER", "COMPANY", "DELIVERY");

    @Autowired
    private WebTestClient webTestClient;

    private final String userId = UUID.randomUUID().toString();
    private final String targetId = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> SECRET);
        for (String service : List.of("user-service", "company-service", "order-service", "delivery-service",
                "hub-service")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                    () -> "http://localhost:" + STUB.getAddress().getPort());
        }
    }

    // 담당자별 권한 표 (#102). 허용 역할은 200(서비스로 전달), 나머지 역할은 403
    private record Rule(HttpMethod method, String uri, Set<String> allowed) {
    }

    private static final String ID = "10000000-0000-4000-8000-000000000001";
    private static final List<Rule> SERVICE_RULES = List.of(
            new Rule(HttpMethod.POST, "/api/v1/companies", Set.of("MASTER", "COMPANY")),
            new Rule(HttpMethod.PATCH, "/api/v1/companies/" + ID, Set.of("MASTER", "COMPANY")),
            new Rule(HttpMethod.DELETE, "/api/v1/companies/" + ID, Set.of("MASTER")),
            new Rule(HttpMethod.POST, "/api/v1/companies/" + ID + "/products", Set.of("MASTER", "COMPANY")),
            new Rule(HttpMethod.PATCH, "/api/v1/products/" + ID, Set.of("MASTER", "COMPANY")),
            new Rule(HttpMethod.DELETE, "/api/v1/products/" + ID, Set.of("MASTER", "COMPANY")),
            new Rule(HttpMethod.DELETE, "/api/v1/orders/" + ID, Set.of("MASTER")),
            new Rule(HttpMethod.PATCH, "/api/v1/deliveries/" + ID + "/status", Set.of("MASTER", "DELIVERY")),
            new Rule(HttpMethod.PATCH, "/api/v1/deliveries/" + ID + "/routes/" + ID + "/status",
                    Set.of("MASTER", "DELIVERY")),
            new Rule(HttpMethod.PATCH, "/api/v1/deliveries/" + ID + "/manager", Set.of("MASTER")),
            new Rule(HttpMethod.PATCH, "/api/v1/deliveries/" + ID + "/routes/" + ID + "/manager", Set.of("MASTER")),
            new Rule(HttpMethod.DELETE, "/api/v1/deliveries/" + ID, Set.of("MASTER")),
            new Rule(HttpMethod.GET, "/api/v1/inventories", Set.of("MASTER")),
            new Rule(HttpMethod.GET, "/api/v1/inventories/" + ID, Set.of("MASTER")),
            new Rule(HttpMethod.GET, "/api/v1/inventories/hubs/" + ID, Set.of("MASTER")),
            new Rule(HttpMethod.GET, "/api/v1/inventories/products/" + ID, Set.of("MASTER", "COMPANY")),
            new Rule(HttpMethod.POST, "/api/v1/inventories/inbound", Set.of("MASTER")),
            new Rule(HttpMethod.PATCH, "/api/v1/inventories/" + ID, Set.of("MASTER")));

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetHits() {
        STUB_HITS.set(0);
    }

    // ---------- MASTER 전용 ----------

    @Test
    void 회원_목록은_MASTER가_아니면_403이고_서비스로_전달하지_않는다() {
        for (String role : NON_MASTER) {
            expectForbidden(HttpMethod.GET, "/api/v1/users", role);
            expectForbidden(HttpMethod.GET, "/api/v1/users?role=MASTER&page=0", role);
        }
        assertThat(STUB_HITS.get()).isZero();
    }

    @Test
    void 회원_정보_수정_역할_변경_상태_변경은_MASTER가_아니면_403() {
        for (String role : NON_MASTER) {
            expectForbidden(HttpMethod.PATCH, "/api/v1/users/" + targetId, role);
            expectForbidden(HttpMethod.PATCH, "/api/v1/users/" + targetId + "/role", role);
            expectForbidden(HttpMethod.PATCH, "/api/v1/users/" + targetId + "/status", role);
        }
        assertThat(STUB_HITS.get()).isZero();
    }

    @Test
    void MASTER는_회원_관리_API를_서비스로_전달한다() {
        request(HttpMethod.GET, "/api/v1/users", "MASTER").expectStatus().isOk();
        request(HttpMethod.PATCH, "/api/v1/users/" + targetId, "MASTER").expectStatus().isOk();
        request(HttpMethod.PATCH, "/api/v1/users/" + targetId + "/role", "MASTER").expectStatus().isOk();
        request(HttpMethod.PATCH, "/api/v1/users/" + targetId + "/status", "MASTER").expectStatus().isOk();
        assertThat(STUB_HITS.get()).isEqualTo(4);
    }

    // ---------- company·order·delivery·hub (담당자별 권한 표) ----------

    @Test
    void 서비스별_규칙은_허용_역할만_서비스로_전달하고_나머지는_403() {
        for (Rule rule : SERVICE_RULES) {
            for (String role : ALL_ROLES) {
                STUB_HITS.set(0);
                WebTestClient.ResponseSpec response = request(rule.method(), rule.uri(), role);
                if (rule.allowed().contains(role)) {
                    response.expectStatus().isOk();
                    assertThat(STUB_HITS.get()).as("%s %s %s", rule.method(), rule.uri(), role).isEqualTo(1);
                } else {
                    expectForbidden(response);
                    assertThat(STUB_HITS.get()).as("%s %s %s", rule.method(), rule.uri(), role).isZero();
                }
            }
        }
    }

    @Test
    void 규칙이_없는_조회와_생성은_모든_역할이_서비스로_전달된다() {
        // 조회는 로그인만, 주문 생성·허브 조회 등은 규칙 없음
        List<Rule> open = List.of(
                new Rule(HttpMethod.GET, "/api/v1/companies", Set.of()),
                new Rule(HttpMethod.GET, "/api/v1/companies/" + ID, Set.of()),
                new Rule(HttpMethod.GET, "/api/v1/products/" + ID, Set.of()),
                new Rule(HttpMethod.POST, "/api/v1/orders", Set.of()),
                new Rule(HttpMethod.GET, "/api/v1/orders/" + ID, Set.of()),
                new Rule(HttpMethod.GET, "/api/v1/deliveries/" + ID, Set.of()),
                new Rule(HttpMethod.GET, "/api/v1/hubs", Set.of()));
        for (Rule rule : open) {
            for (String role : ALL_ROLES) {
                request(rule.method(), rule.uri(), role).expectStatus().isOk();
            }
        }
    }

    // ---------- 규칙 대상이 아닌 경로 (인증만 확인, 권한은 user-service가 판단) ----------

    @Test
    void 회원_상세는_본인_조회가_있어_역할과_관계없이_서비스로_전달한다() {
        for (String role : NON_MASTER) {
            request(HttpMethod.GET, "/api/v1/users/" + userId, role).expectStatus().isOk();
        }
        assertThat(STUB_HITS.get()).isEqualTo(NON_MASTER.length);
    }

    @Test
    void 내_정보_API는_모든_역할이_쓸_수_있다() {
        for (String role : NON_MASTER) {
            request(HttpMethod.GET, "/api/v1/auth/me", role).expectStatus().isOk();
            request(HttpMethod.PATCH, "/api/v1/auth/me", role).expectStatus().isOk();
            request(HttpMethod.PATCH, "/api/v1/auth/me/password", role).expectStatus().isOk();
        }
    }

    @Test
    void 공개_API는_역할_규칙과_관계없이_토큰_없이_통과한다() {
        webTestClient.post().uri("/api/v1/auth/register").exchange().expectStatus().isOk();
        webTestClient.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void 토큰이_없으면_역할_규칙보다_먼저_401() {
        webTestClient.get().uri("/api/v1/users").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
        assertThat(STUB_HITS.get()).isZero();
    }

    private void expectForbidden(HttpMethod method, String uri, String role) {
        expectForbidden(request(method, uri, role));
    }

    private void expectForbidden(WebTestClient.ResponseSpec response) {
        response.expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("FORBIDDEN");
    }

    private WebTestClient.ResponseSpec request(HttpMethod method, String uri, String role) {
        return webTestClient.method(method).uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(SECRET, userId, role))
                .exchange();
    }

    // user-service 대역: 항상 200
    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", GatewayRoleRulesIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        STUB_HITS.incrementAndGet();
        byte[] bytes = "{\"stub\":\"user-service\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
