package com.yuksabal.company.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// company-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum CompanyErrorCode implements ErrorCode {
    // COMPANY 역할이 본인이 관리하지 않는 업체를 생성·수정하거나, 그 업체의 상품을 생성·수정·삭제하려 할 때
    COMPANY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인 업체만 관리할 수 있습니다."),

    // 상품·업체 삭제 시 허브 재고나 진행 중인 주문이 남아 있음 (#137). 무엇이 남았는지는 details로 알려준다
    // 실제 응답 메시지는 대상별로 다르다 (예: "재고나 진행 중인 주문이 있어 상품을 삭제할 수 없습니다.")
    HAS_ACTIVE_WORK(HttpStatus.CONFLICT, "재고나 진행 중인 주문이 있어 삭제할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
