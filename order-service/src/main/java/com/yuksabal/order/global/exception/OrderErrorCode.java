package com.yuksabal.order.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// order-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    // 기능 개발 시 추가 (예: ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "...을 찾을 수 없습니다."))
    ;

    private final HttpStatus status;
    private final String message;
}
