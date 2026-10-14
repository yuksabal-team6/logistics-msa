package com.yuksabal.gateway.global.exception;

import com.yuksabal.gateway.global.response.ErrorResponseWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;

// 게이트웨이 자체에서 난 오류를 공통 JSON 형식으로 바꾼다. 하위 서비스가 내려준 응답(4xx·5xx)은 그대로 전달되므로 여기 오지 않는다.
// - 라우트 없음 → 404 RESOURCE_NOT_FOUND
// - Eureka 인스턴스 없음(NotFoundException 503) / 연결 실패 → 503 SERVICE_UNAVAILABLE
// 그 외 예외는 Spring 기본 핸들러(-1)에 그대로 넘긴다. 예외 메시지는 응답에 넣지 않는다.
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final ErrorResponseWriter errorResponseWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        GatewayErrorCode errorCode = resolve(ex);
        if (errorCode == null) {
            return Mono.error(ex);
        }
        if (errorCode == GatewayErrorCode.SERVICE_UNAVAILABLE) {
            log.warn("Downstream unavailable: {} {} ({})", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), ex.getClass().getSimpleName());
        }
        return errorResponseWriter.write(exchange, errorCode);
    }

    private GatewayErrorCode resolve(Throwable ex) {
        if (ex instanceof ResponseStatusException statusException) {
            if (statusException.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return GatewayErrorCode.RESOURCE_NOT_FOUND;
            }
            if (statusException.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
                return GatewayErrorCode.SERVICE_UNAVAILABLE;
            }
        }
        if (hasCause(ex, ConnectException.class)) {
            return GatewayErrorCode.SERVICE_UNAVAILABLE;
        }
        return null;
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
