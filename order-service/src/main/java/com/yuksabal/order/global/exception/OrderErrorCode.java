package com.yuksabal.order.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// order-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    // 기능 개발 시 추가 (예: INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "변경할 수 없는 주문 상태입니다."))
    // 404는 CommonErrorCode.RESOURCE_NOT_FOUND를 사용하고 리소스별 NOT_FOUND 코드는 만들지 않는다
    ;

    private final HttpStatus status;
    private final String message;
}
