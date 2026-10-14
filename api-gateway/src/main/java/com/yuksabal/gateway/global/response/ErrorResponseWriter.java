package com.yuksabal.gateway.global.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.gateway.global.exception.GatewayErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// 인증 필터·예외 핸들러가 공통 JSON 오류 응답을 직접 쓸 때 사용
@Component
@RequiredArgsConstructor
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public Mono<Void> write(ServerWebExchange exchange, GatewayErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ErrorResponse.of(errorCode));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        response.setStatusCode(errorCode.getStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
