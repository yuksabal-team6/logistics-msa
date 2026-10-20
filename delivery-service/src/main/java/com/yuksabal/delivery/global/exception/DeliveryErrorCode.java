package com.yuksabal.delivery.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// delivery-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum DeliveryErrorCode implements ErrorCode {
    // 기능 개발 시 추가 (예: DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "...을 찾을 수 없습니다."))
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 변경할 수 없습니다."),
    DELIVERY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "배송에 대한 권한이 없습니다."),
    DELIVERY_CONFLICT(HttpStatus.CONFLICT, "다른 요청이 먼저 배송을 변경했습니다. 다시 조회 후 시도해 주세요."),
    DELIVERY_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 배송이 완료되었습니다.");

    private final HttpStatus status;
    private final String message;
}
