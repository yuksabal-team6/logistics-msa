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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 설정(auth.user-status-cache.ttl=5s)으로 회원 상태 조회 결과가 캐시되는지 확인한다.
// 만료·동시 요청 등 세부 동작은 UserStatusCacheTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
class GatewayUserStatusCacheIntegrationTest {

    private static final String SECRET = TestJwt.randomBase64Secret(32);
    private static final AtomicInteger LOOKUP_HITS = new AtomicInteger();
    // user-service 회원 조회 응답 상태 (200이면 활성 USER)
    private static final AtomicInteger LOOKUP_STATUS = new AtomicInteger(200);
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
        LOOKUP_HITS.set(0);
        LOOKUP_STATUS.set(200);
    }

    @Test
    void 같은_회원의_연속_요청은_user_service를_한_번만_조회한다() {
        for (int i = 0; i < 3; i++) {
            get().expectStatus().isOk();
        }
        assertThat(LOOKUP_HITS.get()).isEqualTo(1);
    }

    @Test
    void user_service_장애_결과는_캐시하지_않아_복구되면_바로_통과한다() {
        LOOKUP_STATUS.set(500);
        get().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.error.code").isEqualTo("USER_SERVICE_UNAVAILABLE");

        LOOKUP_STATUS.set(200);
        get().expectStatus().isOk();
        assertThat(LOOKUP_HITS.get()).isEqualTo(2);
    }

    private WebTestClient.ResponseSpec get() {
        return webTestClient.get().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(SECRET, userId, "USER"))
                .exchange();
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", GatewayUserStatusCacheIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // /internal/users/{id} → 회원 상태, 그 외 → 하위 서비스 응답(200)
    private static void handle(HttpExchange exchange) throws IOException {
        int status = 200;
        String body = "{\"success\":true}";
        if (exchange.getRequestURI().getPath().startsWith("/internal/users/")) {
            LOOKUP_HITS.incrementAndGet();
            status = LOOKUP_STATUS.get();
            body = status == 200
                    ? "{\"success\":true,\"data\":{\"active\":true,\"role\":\"USER\"}}"
                    : "{\"success\":false}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
