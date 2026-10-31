package com.yuksabal.gateway.global.exception;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuksabal.gateway.support.TestJwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

// 실제 라우팅에서 하위 서비스 응답 지연·연결 끊김이 공통 JSON(504·502)으로 바뀌는지 확인한다.
// 공개 경로(POST /api/v1/auth/login → user-service stub)를 써서 회원 상태 조회 없이 하위 서비스 동작만 본다.
// 응답 시간 제한은 테스트 시간을 줄이려고 1초로 낮춘다(운영 5초).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.gateway.httpclient.response-timeout=1s"
})
class GatewayErrorHandlingIntegrationTest {

    private static final String MODE_HEADER = "X-Stub-Mode";
    private static final HttpServer STUB = startStub();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://localhost:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void setUp() {
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void 정상_응답은_그대로_전달한다() {
        login("ok").expectStatus().isOk();
    }

    @Test
    void 응답_시간을_넘기면_504_공통_JSON() {
        expectError(login("slow"), HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT");
    }

    @Test
    void 하위_서비스가_응답_없이_연결을_끊으면_502_공통_JSON() {
        expectError(login("close"), HttpStatus.BAD_GATEWAY, "DEPENDENCY_FAILURE");
    }

    @Test
    void 하위_서비스의_500_응답은_바꾸지_않고_그대로_전달한다() {
        login("error").expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody().jsonPath("$.error.code").isEqualTo("ORDER_FAILED");
    }

    private WebTestClient.ResponseSpec login(String mode) {
        return webTestClient.post().uri("/api/v1/auth/login").header(MODE_HEADER, mode).exchange();
    }

    private static void expectError(WebTestClient.ResponseSpec response, HttpStatus status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo(code)
                .jsonPath("$.error.message").isEqualTo(GatewayErrorCode.valueOf(code).getMessage());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", GatewayErrorHandlingIntegrationTest::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String mode = exchange.getRequestHeaders().getFirst(MODE_HEADER);
        exchange.getRequestBody().readAllBytes();
        switch (mode == null ? "ok" : mode) {
            case "slow" -> {
                sleep(3000);
                respond(exchange, 200, "{\"success\":true}");
            }
            case "close" -> exchange.close();
            case "error" -> respond(exchange, 500, "{\"success\":false,\"error\":{\"code\":\"ORDER_FAILED\"}}");
            default -> respond(exchange, 200, "{\"success\":true}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // 게이트웨이가 먼저 끊은 경우(slow)
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
