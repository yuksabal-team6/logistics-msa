package com.yuksabal.company.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// company-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum CompanyErrorCode implements ErrorCode {
    // COMPANY 역할이 본인 소유가 아닌 업체를 생성·수정·삭제하려 할 때
    COMPANY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인 업체만 관리할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
