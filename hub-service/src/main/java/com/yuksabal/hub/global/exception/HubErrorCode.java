package com.yuksabal.hub.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// hub-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum HubErrorCode implements ErrorCode {
    // 기능 개발 시 추가 (예: INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."))
    // 404는 CommonErrorCode.RESOURCE_NOT_FOUND를 사용하고 리소스별 NOT_FOUND 코드는 만들지 않는다
    ;

    private final HttpStatus status;
    private final String message;
}
