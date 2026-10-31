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
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;

// 게이트웨이 자체에서 난 오류를 공통 JSON 형식으로 바꾼다. 하위 서비스가 내려준 응답(4xx·5xx)은 그대로 전달되므로 여기 오지 않는다.
// - 라우트 없음 → 404 RESOURCE_NOT_FOUND
// - Eureka 인스턴스 없음(NotFoundException 503) / 연결 실패·연결 시간 초과 → 503 SERVICE_UNAVAILABLE
// - 응답 시간 초과(httpclient.response-timeout, 게이트웨이가 504로 바꿈) → 504 GATEWAY_TIMEOUT
// - 하위 서비스가 응답 도중 연결을 끊음 → 502 DEPENDENCY_FAILURE
// - 그 밖의 예상 못 한 예외 → 500 INTERNAL_SERVER_ERROR
// 그 외 상태 예외(400·405 등 요청 문제)는 Spring 기본 핸들러(-1)에 그대로 넘긴다. 예외 메시지는 응답에 넣지 않는다.
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
        log(exchange, ex, errorCode);
        return errorResponseWriter.write(exchange, errorCode);
    }

    private GatewayErrorCode resolve(Throwable ex) {
        if (ex instanceof ResponseStatusException statusException) {
            return resolveStatus(statusException);
        }
        if (hasCause(ex, ConnectException.class)) {
            return GatewayErrorCode.SERVICE_UNAVAILABLE;
        }
        if (hasCause(ex, PrematureCloseException.class)) {
            return GatewayErrorCode.DEPENDENCY_FAILURE;
        }
        return GatewayErrorCode.INTERNAL_SERVER_ERROR;
    }

    private GatewayErrorCode resolveStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            return null;
        }
        return switch (status) {
            case NOT_FOUND -> GatewayErrorCode.RESOURCE_NOT_FOUND;
            case SERVICE_UNAVAILABLE -> GatewayErrorCode.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> GatewayErrorCode.GATEWAY_TIMEOUT;
            case BAD_GATEWAY -> GatewayErrorCode.DEPENDENCY_FAILURE;
            case INTERNAL_SERVER_ERROR -> GatewayErrorCode.INTERNAL_SERVER_ERROR;
            default -> null;
        };
    }

    private static void log(ServerWebExchange exchange, Throwable ex, GatewayErrorCode errorCode) {
        if (errorCode == GatewayErrorCode.INTERNAL_SERVER_ERROR) {
            log.error("Unhandled gateway error: {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), ex);
        } else if (errorCode.getStatus().is5xxServerError()) {
            log.warn("Downstream error {}: {} {} ({})", errorCode.name(), exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), ex.getClass().getSimpleName());
        }
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
