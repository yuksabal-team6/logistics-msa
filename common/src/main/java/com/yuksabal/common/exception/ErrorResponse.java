package com.yuksabal.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "에러 응답")
public record ErrorResponse(
        @Schema(description = "에러 코드", example = "INVALID_INPUT")
        String code,
        @Schema(description = "에러 메시지", example = "입력값이 올바르지 않습니다.")
        String message
) {

    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }

    // 기본 메시지 대신 상세 메시지를 내려줄 때 사용
    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}
