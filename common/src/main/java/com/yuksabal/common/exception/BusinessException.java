package com.yuksabal.common.exception;

import com.yuksabal.common.response.ApiErrorDetail;
import lombok.Getter;

import java.util.List;

// 예: throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않거나 삭제된 상품입니다.", "productId");
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ApiErrorDetail> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), List.of());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    // 문제가 된 필드를 details로 함께 내려줄 때 사용
    public BusinessException(ErrorCode errorCode, String message, String field) {
        this(errorCode, message, List.of(new ApiErrorDetail(field, message)));
    }

    public BusinessException(ErrorCode errorCode, String message, List<ApiErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
