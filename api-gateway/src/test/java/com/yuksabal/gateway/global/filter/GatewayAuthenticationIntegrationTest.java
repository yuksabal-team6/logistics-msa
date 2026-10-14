package com.yuksabal.gateway.global.filter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuksabal.gateway.support.TestJwt;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 게이트웨이 서버 + 하위 서비스 대역(stub)으로 인증·인가·헤더 전달·오류 응답을 확인한다.
// - order-service, user-service → stub (받은 X-User-* 헤더를 그대로 돌려준다)
// - hub-service → 닫힌 포트 (연결 실패)
// - delivery-service, company-service → 인스턴스 없음
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        // 테스트 전용 역할 규칙 (운영 설정에는 아직 규칙이 없다)
        "auth.role-rules[0].method=GET",
        "auth.role-rules[0].path=/api/v1/orders/master-only/**",
        "auth.role-rules[0].roles[0]=MASTER"
})
class GatewayAuthenticationIntegrationTest {

    private static final String SECRET = TestJwt.randomBase64Secret(32);
    private static final HttpServer STUB = startStub();
    private static final AtomicInteger STUB_HITS = new AtomicInteger();

    @Autowired
    private WebTestClient webTestClient;

    private final String userId = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String stubUri = "http://localhost:" + STUB.getAddress().getPort();
        registry.add("jwt.secret", () -> SECRET);
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri", () -> stubUri);
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", () -> stubUri);
        registry.add("spring.cloud.discovery.client.simple.instances.hub-service[0].uri",
                () -> "http://localhost:" + closedPort());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetHits() {
        STUB_HITS.set(0);
    }

    @Test
    void 유효한_JWT면_하위_서비스로_전달하고_사용자_헤더를_설정() {
        get("/api/v1/orders", token("USER"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("USER")
                .jsonPath("$.path").isEqualTo("/api/v1/orders");
    }

    @Test
    void 클라이언트가_위조한_사용자_헤더는_토큰_값으로_덮어쓴다() {
        webTestClient.get().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("USER"))
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .header("x-user-role", "MASTER")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("USER");
    }

    @Test
    void 공개_API는_토큰_없이_통과하고_위조_헤더는_제거된다() {
        webTestClient.post().uri("/api/v1/auth/login")
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .header("X-User-Role", "MASTER")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("")
                .jsonPath("$.userRole").isEqualTo("");
    }

    @Test
    void 공개_경로라도_메서드가_다르면_인증_필요() {
        get("/api/v1/auth/login", null).expectStatus().isUnauthorized();
    }

    @Test
    void Authorization_헤더가_없으면_401() {
        expectError(get("/api/v1/orders", null), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertStubNotCalled();
    }

    @Test
    void Bearer_형식이_아니면_401() {
        webTestClient.get().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .exchange()
                .expectStatus().isUnauthorized();
        assertStubNotCalled();
    }

    @Test
    void 다른_키로_서명된_JWT는_401() {
        String forged = TestJwt.token(TestJwt.randomBase64Secret(32), userId, "MASTER");

        expectError(get("/api/v1/orders", forged), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertStubNotCalled();
    }

    @Test
    void 만료된_JWT는_401_TOKEN_EXPIRED() {
        Instant past = Instant.now().minusSeconds(7200);
        String expired = TestJwt.validBuilder(userId, "USER")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(3600)))
                .signWith(TestJwt.key(SECRET), Jwts.SIG.HS256).compact();

        expectError(get("/api/v1/orders", expired), HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    }

    @Test
    void iss가_다르거나_허용되지_않은_role이면_401() {
        String wrongIssuer = TestJwt.validBuilder(userId, "USER").issuer("hacker")
                .signWith(TestJwt.key(SECRET), Jwts.SIG.HS256).compact();

        expectError(get("/api/v1/orders", wrongIssuer), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        expectError(get("/api/v1/orders", token("ADMIN")), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertStubNotCalled();
    }

    @Test
    void 역할이_부족하면_403() {
        expectError(get("/api/v1/orders/master-only/1", token("USER")), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertStubNotCalled();
    }

    @Test
    void 역할이_맞으면_통과() {
        get("/api/v1/orders/master-only/1", token("MASTER")).expectStatus().isOk();
    }

    @Test
    void Eureka_인스턴스가_없으면_503() {
        expectError(get("/api/v1/deliveries", token("USER")), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE")
                .jsonPath("$.error.message").value(message ->
                        assertThat(message.toString()).doesNotContain("instance"));
    }

    @Test
    void 대상_서비스에_연결할_수_없으면_503() {
        expectError(get("/api/v1/hubs", token("USER")), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
    }

    @Test
    void 존재하지_않는_경로는_인증_후_404() {
        expectError(get("/api/v1/unknown", token("USER")), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void 존재하지_않는_경로도_토큰이_없으면_401() {
        get("/api/v1/unknown", null).expectStatus().isUnauthorized();
    }

    @Test
    void 내부_API_경로는_외부에서_404() {
        expectError(get("/internal/hubs", token("MASTER")), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertStubNotCalled();
    }

    @Test
    void 공개_패턴을_이용한_경로_조작은_하위_서비스로_전달되지_않는다() {
        get("/swagger-ui/../api/v1/orders", null).expectStatus().isNotFound();
        assertStubNotCalled();
    }

    @Test
    void 하위_서비스가_내려준_오류_응답은_그대로_전달한다() {
        get("/api/v1/orders/boom", token("USER"))
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody().jsonPath("$.stub").isEqualTo("downstream-error");
    }

    private WebTestClient.ResponseSpec get(String uri, String token) {
        WebTestClient.RequestHeadersSpec<?> spec = webTestClient.get().uri(uri);
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.exchange();
    }

    private WebTestClient.BodyContentSpec expectError(WebTestClient.ResponseSpec response, HttpStatus status, String code) {
        return response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo(code)
                .jsonPath("$.error.message").isNotEmpty();
    }

    private String token(String role) {
        return TestJwt.token(SECRET, userId, role);
    }

    private void assertStubNotCalled() {
        assertThat(STUB_HITS.get()).isZero();
    }

    // 하위 서비스 대역: 받은 사용자 헤더와 경로를 JSON으로 돌려준다. /boom은 500
    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", GatewayAuthenticationIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        STUB_HITS.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        int status = path.endsWith("/boom") ? 500 : 200;
        String body = status == 500
                ? "{\"stub\":\"downstream-error\"}"
                : "{\"userId\":\"" + joined(exchange, "X-User-Id") + "\",\"userRole\":\"" + joined(exchange, "X-User-Role")
                + "\",\"path\":\"" + path + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // 같은 헤더가 여러 개 오면 쉼표로 이어 붙여 위조 값이 섞였는지 드러나게 한다
    private static String joined(HttpExchange exchange, String header) {
        List<String> values = exchange.getRequestHeaders().get(header);
        return values == null ? "" : String.join(",", values);
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
