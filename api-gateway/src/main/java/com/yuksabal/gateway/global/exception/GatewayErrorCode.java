package com.yuksabal.gateway.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// gateway는 common을 의존하지 않으므로 필요한 코드만 둔다. 공통 코드의 상태·메시지는 common CommonErrorCode와 같게 유지한다.
@Getter
@RequiredArgsConstructor
public enum GatewayErrorCode {
    // common CommonErrorCode와 동일
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    // gateway 도메인 에러 (API 명세 0.5)
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다. 다시 로그인해주세요.");

    private final HttpStatus status;
    private final String message;
}
