package com.yuksabal.gateway.global.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.gateway.global.response.ErrorResponseWriter;
import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 게이트웨이 예외 → 공통 JSON 오류 코드 매핑 확인
class GatewayErrorWebExceptionHandlerTest {

    private final GatewayErrorWebExceptionHandler handler =
            new GatewayErrorWebExceptionHandler(new ErrorResponseWriter(new ObjectMapper()));

    static Stream<Arguments> handledErrors() {
        return Stream.of(
                Arguments.of(new ResponseStatusException(HttpStatus.NOT_FOUND), GatewayErrorCode.RESOURCE_NOT_FOUND),
                Arguments.of(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE), GatewayErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT), GatewayErrorCode.GATEWAY_TIMEOUT),
                Arguments.of(new ResponseStatusException(HttpStatus.BAD_GATEWAY), GatewayErrorCode.DEPENDENCY_FAILURE),
                Arguments.of(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR), GatewayErrorCode.INTERNAL_SERVER_ERROR),
                Arguments.of(new RuntimeException(new ConnectException("refused")), GatewayErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of(new ConnectTimeoutException("connect timed out"), GatewayErrorCode.SERVICE_UNAVAILABLE),
                Arguments.of(new IllegalStateException("unexpected"), GatewayErrorCode.INTERNAL_SERVER_ERROR)
        );
    }

    @ParameterizedTest
    @MethodSource("handledErrors")
    void 게이트웨이_오류는_공통_JSON으로_응답한다(Throwable ex, GatewayErrorCode expected) {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, ex).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(expected.getStatus());
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"success\":false")
                .contains("\"code\":\"" + expected.name() + "\"")
                .doesNotContain("refused", "timed out", "unexpected");
    }

    @Test
    void 요청_문제인_상태_예외는_기본_핸들러로_넘긴다() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED);

        assertThatThrownBy(() -> handler.handle(exchange(), ex).block()).isSameAs(ex);
    }

    @Test
    void 이미_응답을_보내기_시작했으면_건드리지_않는다() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();
        IllegalStateException ex = new IllegalStateException("late");

        assertThatThrownBy(() -> handler.handle(exchange, ex).block()).isSameAs(ex);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));
    }
}
