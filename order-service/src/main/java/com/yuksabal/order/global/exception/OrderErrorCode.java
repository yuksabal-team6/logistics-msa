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

    // 허용되지 않은 상태 전이. 예: CONFIRMED가 아닌 주문의 완료 처리
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 변경할 수 없습니다."),

    // 삭제: 진행 중이거나 보상이 끝나지 않은 주문
    ORDER_NOT_DELETABLE(HttpStatus.CONFLICT, "삭제할 수 없는 주문 상태입니다."),

    // API 스펙만 먼저 만든 상태 (이슈 #47). 기능 구현 후 사용처가 없으면 삭제
    // TODO 이슈 51(주문 취소) 구현 후 사용처가 없어지면 삭제 (OrderController import, @ApiErrorCodes 포함)
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "아직 구현되지 않은 기능입니다."),

    // 참고용 sample api 전용
    // TODO sample 패키지 삭제할 때 같이 삭제
    SAMPLE_NOT_FOUND(HttpStatus.NOT_FOUND, "샘플을 찾을 수 없습니다."),
    ;

    private final HttpStatus status;
    private final String message;
}
