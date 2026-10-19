package com.yuksabal.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

// 게이트웨이(기본 http://localhost:8080)로 실제 HTTP 요청을 보낸다. 토큰은 호출하는 쪽이 넘긴다 (null이면 헤더 없음)
public final class Gateway {

    private static final String BASE_URL = System.getProperty("it.baseUrl", "http://localhost:8080");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Gateway() {
    }

    public static Response get(String path, String token) {
        return send(builder(path, token).GET());
    }

    public static Response post(String path, String token, Object body) {
        return send(builder(path, token).POST(json(body)));
    }

    // 주문 생성 전용: Idempotency-Key 헤더를 붙인다
    public static Response postWithIdempotencyKey(String path, String token, Object body, UUID idempotencyKey) {
        return send(builder(path, token).header("Idempotency-Key", idempotencyKey.toString()).POST(json(body)));
    }

    public static Response patch(String path, String token, Object body) {
        return send(builder(path, token).method("PATCH", json(body)));
    }

    public static Response delete(String path, String token) {
        return send(builder(path, token).DELETE());
    }

    private static HttpRequest.Builder builder(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static HttpRequest.BodyPublisher json(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(body == null ? "" : MAPPER.writeValueAsString(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isBlank() ? MAPPER.nullNode() : MAPPER.readTree(response.body());
            return new Response(request.method() + " " + request.uri().getPath(), response.statusCode(), body);
        } catch (IOException e) {
            throw new IllegalStateException("게이트웨이에 연결하지 못했습니다. docker compose로 서비스를 먼저 띄워 주세요: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // 실패 메시지에 요청과 응답 본문이 함께 나오도록 toString을 둔다
    public record Response(String request, int status, JsonNode body) {

        public JsonNode data() {
            return body.path("data");
        }

        public JsonNode error() {
            return body.path("error");
        }

        public UUID uuid(String field) {
            return UUID.fromString(data().path(field).asText());
        }

        // 기대한 상태 코드가 아니면 요청·응답을 담아 바로 실패시킨다 (준비 단계에서 쓴다)
        public Response expect(int expectedStatus) {
            if (status != expectedStatus) {
                throw new AssertionError("기대 " + expectedStatus + ", 실제 " + this);
            }
            return this;
        }

        @Override
        public String toString() {
            return status + " ← " + request + " " + body;
        }
    }
}
