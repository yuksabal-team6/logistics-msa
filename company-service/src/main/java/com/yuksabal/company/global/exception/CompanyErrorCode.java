package com.yuksabal.company.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// company-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum CompanyErrorCode implements ErrorCode {
    // 기능 개발 시 추가 (예: COMPANY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 업체에 대한 권한이 없습니다."))
    // 404는 CommonErrorCode.RESOURCE_NOT_FOUND를 사용하고 리소스별 NOT_FOUND 코드는 만들지 않는다
    ;

    private final HttpStatus status;
    private final String message;
}
