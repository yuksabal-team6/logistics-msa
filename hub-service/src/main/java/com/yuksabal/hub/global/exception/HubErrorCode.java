package com.yuksabal.hub.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// hub-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum HubErrorCode implements ErrorCode {
    // 404는 CommonErrorCode.RESOURCE_NOT_FOUND를 쓰고, 여기에는 허브 도메인 에러만 추가한다
    INVENTORY_CONFLICT(HttpStatus.CONFLICT, "재고 데이터가 중복되어 있습니다.");

    private final HttpStatus status;
    private final String message;
}
