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

    INVENTORY_CONFLICT(HttpStatus.CONFLICT, "재고 데이터가 중복되어 있습니다."),

    // 주문 재고 차감 시 남은 수량이 주문 수량보다 적은 경우. order는 주문 FAILED + 보상 NOT_REQUIRED로 처리한다
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),

    // 관리자가 조회한 뒤 주문 차감·입고 등으로 수량이 바뀐 경우
    INVENTORY_QUANTITY_CONFLICT(HttpStatus.CONFLICT, "재고 수량이 변경되었습니다. 현재 수량을 확인한 뒤 다시 조정해 주세요.");

    private final HttpStatus status;
    private final String message;
}
