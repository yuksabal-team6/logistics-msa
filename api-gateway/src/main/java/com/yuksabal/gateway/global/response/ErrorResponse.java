package com.yuksabal.gateway.global.response;

import com.yuksabal.gateway.global.exception.GatewayErrorCode;

// common ApiResponse 실패 형식과 같은 JSON: {success: false, error: {code, message}}
// common은 details가 비어 있으면 생략하므로 gateway 오류(필드 정보 없음)도 details를 내리지 않는다.
public record ErrorResponse(boolean success, Error error) {

    public record Error(String code, String message) {
    }

    public static ErrorResponse of(GatewayErrorCode errorCode) {
        return new ErrorResponse(false, new Error(errorCode.name(), errorCode.getMessage()));
    }
}
