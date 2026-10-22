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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 UserStatusClient로 user-service 내부 회원 조회를 거쳐 토큰 발급 뒤 바뀐 회원 상태가 즉시 반영되는지 확인한다.
// - user-service → stub: /internal/users/{id}는 테스트가 정한 응답, 그 외 경로는 하위 서비스 응답(200)
// - order-service → 같은 stub (하위 서비스로 전달됐는지 DOWNSTREAM_HITS로 센다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "auth.role-rules[0].method=GET",
        "auth.role-rules[0].path=/api/v1/orders/master-only/**",
        "auth.role-rules[0].roles[0]=MASTER"
})
class GatewayUserStatusIntegrationTest {

    private static final String SECRET = TestJwt.randomBase64Secret(32);
    private static final Map<String, StubResponse> USER_RESPONSES = new ConcurrentHashMap<>();
    private static final AtomicInteger DOWNSTREAM_HITS = new AtomicInteger();
    private static final AtomicInteger LOOKUP_HITS = new AtomicInteger();
    private static final HttpServer STUB = startStub();

    @Autowired
    private WebTestClient webTestClient;

    private final String userId = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String stubUri = "http://localhost:" + STUB.getAddress().getPort();
        registry.add("jwt.secret", () -> SECRET);
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", () -> stubUri);
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri", () -> stubUri);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void reset() {
        USER_RESPONSES.clear();
        DOWNSTREAM_HITS.set(0);
        LOOKUP_HITS.set(0);
        // 응답 타임아웃(2초) 시험이 있으므로 시간 여유를 둔다
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void 활성_회원이고_역할이_같으면_하위_서비스로_전달한다() {
        userResponds(200, userBody(true, "USER"));

        get("/api/v1/orders", "USER")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("USER");
        assertThat(LOOKUP_HITS.get()).isEqualTo(1);
        assertThat(DOWNSTREAM_HITS.get()).isEqualTo(1);
    }

    @Test
    void 탈퇴한_회원은_401() {
        userResponds(404, "{\"success\":false,\"error\":{\"code\":\"RESOURCE_NOT_FOUND\"}}");

        expectRejected("USER", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    void 비활성_회원은_403_ACCOUNT_DISABLED() {
        userResponds(200, userBody(false, "USER"));

        expectRejected("USER", HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
    }

    @Test
    void 비활성이면서_역할도_바뀐_회원은_비활성을_먼저_알린다() {
        userResponds(200, userBody(false, "MASTER"));

        expectRejected("USER", HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
    }

    @Test
    void 강등된_회원의_예전_토큰은_401_ROLE_CHANGED() {
        userResponds(200, userBody(true, "USER"));

        expectRejected("MASTER", HttpStatus.UNAUTHORIZED, "ROLE_CHANGED");
    }

    @Test
    void 승격된_회원도_403이_아니라_401_ROLE_CHANGED로_재로그인시킨다() {
        userResponds(200, userBody(true, "MASTER"));

        expectError(get("/api/v1/orders/master-only/1", "USER"), HttpStatus.UNAUTHORIZED, "ROLE_CHANGED");
        assertThat(DOWNSTREAM_HITS.get()).isZero();
    }

    @Test
    void 역할_규칙은_상태_확인을_통과한_뒤_적용한다() {
        userResponds(200, userBody(true, "USER"));
        expectError(get("/api/v1/orders/master-only/1", "USER"), HttpStatus.FORBIDDEN, "FORBIDDEN");

        userResponds(200, userBody(true, "MASTER"));
        get("/api/v1/orders/master-only/1", "MASTER").expectStatus().isOk();
        assertThat(DOWNSTREAM_HITS.get()).isEqualTo(1);
    }

    @Test
    void user_service가_오류를_내면_503() {
        userResponds(500, "{\"success\":false}");

        expectRejected("USER", HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE");
    }

    @Test
    void user_service_응답_형식이_다르면_503() {
        userResponds(200, "{\"success\":true,\"data\":null}");

        expectRejected("USER", HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE");
    }

    @Test
    void user_service_응답이_2초를_넘으면_503() {
        USER_RESPONSES.put(userId, new StubResponse(200, userBody(true, "USER"), 3000));

        expectRejected("USER", HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE");
    }

    @Test
    void 공개_API는_회원_상태를_조회하지_않는다() {
        webTestClient.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        assertThat(LOOKUP_HITS.get()).isZero();
    }

    @Test
    void 토큰이_잘못되면_회원_상태를_조회하지_않는다() {
        webTestClient.get().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(TestJwt.randomBase64Secret(32), userId, "USER"))
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(LOOKUP_HITS.get()).isZero();
    }

    @Test
    void 하위_서비스_오류는_회원_조회_실패로_바꾸지_않고_그대로_전달한다() {
        userResponds(200, userBody(true, "USER"));

        get("/api/v1/orders/boom", "USER")
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody().jsonPath("$.stub").isEqualTo("downstream-error");
    }

    private void expectRejected(String tokenRole, HttpStatus status, String code) {
        expectError(get("/api/v1/orders", tokenRole), status, code);
        assertThat(DOWNSTREAM_HITS.get()).isZero();
    }

    private void expectError(WebTestClient.ResponseSpec response, HttpStatus status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo(code)
                .jsonPath("$.error.message").isNotEmpty();
    }

    private WebTestClient.ResponseSpec get(String uri, String tokenRole) {
        return webTestClient.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(SECRET, userId, tokenRole))
                .exchange();
    }

    private void userResponds(int status, String body) {
        USER_RESPONSES.put(userId, new StubResponse(status, body, 0));
    }

    // user-service 내부 응답 모양 (common ApiResponse)
    private String userBody(boolean active, String role) {
        return "{\"success\":true,\"data\":{\"userId\":\"" + userId + "\",\"username\":\"tester1\",\"active\":" + active
                + ",\"role\":\"" + role + "\",\"hubId\":null,\"companyId\":null,\"slackId\":\"U1\"},\"message\":\"ok\"}";
    }

    private record StubResponse(int status, String body, long delayMillis) {
    }

    // 타임아웃 시험 중에도 다른 요청을 받을 수 있게 스레드 풀을 쓴다
    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", GatewayUserStatusIntegrationTest::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        StubResponse response;
        if (path.startsWith("/internal/users/")) {
            LOOKUP_HITS.incrementAndGet();
            response = USER_RESPONSES.getOrDefault(path.substring("/internal/users/".length()),
                    new StubResponse(404, "{\"success\":false}", 0));
        } else {
            DOWNSTREAM_HITS.incrementAndGet();
            response = path.endsWith("/boom")
                    ? new StubResponse(500, "{\"stub\":\"downstream-error\"}", 0)
                    : new StubResponse(200, "{\"userId\":\"" + header(exchange, "X-User-Id") + "\",\"userRole\":\""
                    + header(exchange, "X-User-Role") + "\"}", 0);
        }
        try {
            if (response.delayMillis() > 0) {
                Thread.sleep(response.delayMillis());
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 게이트웨이가 타임아웃으로 연결을 끊은 경우
        }
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }
}
