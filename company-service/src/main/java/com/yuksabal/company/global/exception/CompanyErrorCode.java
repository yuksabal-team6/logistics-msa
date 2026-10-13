package com.yuksabal.company.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// company-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum CompanyErrorCode implements ErrorCode {
    // 기능 개발 시 추가 (예: COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "...을 찾을 수 없습니다."))
    ;

    private final HttpStatus status;
    private final String message;
}
