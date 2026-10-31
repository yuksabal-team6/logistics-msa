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
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    DEPENDENCY_FAILURE(HttpStatus.BAD_GATEWAY, "다른 서비스 호출에 실패했습니다."),
    // gateway 도메인 에러 (API 명세 0.5)
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다. 다시 로그인해주세요."),
    // 하위 서비스 응답 시간 초과. 하위 서비스는 처리를 계속했을 수 있어 바로 재시도하면 중복될 수 있다
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "서비스 응답이 지연되었습니다. 처리 결과를 확인한 뒤 다시 시도해주세요."),
    // 회원 상태 확인 (토큰 발급 뒤 바뀐 상태를 즉시 반영). ACCOUNT_DISABLED는 user-service UserErrorCode와 같게 유지한다
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),
    ROLE_CHANGED(HttpStatus.UNAUTHORIZED, "권한이 변경되었습니다. 다시 로그인해주세요."),
    USER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String message;
}
