package com.yuksabal.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yuksabal.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

// 성공·실패 공통 응답. null 필드는 JSON에서 빠진다.
// 성공: {success: true, data, message} / 실패: {success: false, code, message}
@Schema(description = "공통 응답")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        @Schema(description = "성공 여부", example = "true")
        boolean success,
        @Schema(description = "에러 코드 (실패 시)", example = "INVALID_INPUT")
        String code,
        @Schema(description = "응답 데이터 (성공 시)")
        T data,
        @Schema(description = "응답 메시지", example = "요청이 성공했습니다.")
        String message
) {

    private static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공했습니다.";

    // 반환할 데이터가 없으면 data에 null을 넘긴다
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, null, data, DEFAULT_SUCCESS_MESSAGE);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, null, data, message);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.name(), null, errorCode.getMessage());
    }

    // 기본 메시지 대신 상세 메시지를 내려줄 때 사용
    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.name(), null, message);
    }

    // 다른 서비스가 내려준 에러 코드를 그대로 전달할 때 사용 (Feign 4xx)
    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(false, code, null, message);
    }
}
