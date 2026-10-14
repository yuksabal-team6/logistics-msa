package com.yuksabal.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yuksabal.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 성공·실패 공통 응답. null 필드는 JSON에서 빠진다.
// 성공: {success: true, data, message} / 실패: {success: false, error: {code, message, details}}
@Schema(description = "공통 응답")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        @Schema(description = "성공 여부", example = "true")
        boolean success,
        @Schema(description = "응답 데이터 (성공 시)")
        T data,
        @Schema(description = "응답 메시지 (성공 시)", example = "요청이 성공했습니다.")
        String message,
        @Schema(description = "오류 정보 (실패 시)")
        ApiError error
) {

    private static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공했습니다.";

    // 반환할 데이터가 없으면 data에 null을 넘긴다
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, DEFAULT_SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode.name(), errorCode.getMessage(), List.of());
    }

    // 기본 메시지 대신 상세 메시지를 내려줄 때 사용
    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.name(), message, List.of());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message, List<ApiErrorDetail> details) {
        return fail(errorCode.name(), message, details);
    }

    // 다른 서비스가 내려준 오류를 그대로 전달할 때 사용 (Feign 4xx)
    public static ApiResponse<Void> fail(String code, String message, List<ApiErrorDetail> details) {
        return new ApiResponse<>(false, null, null, new ApiError(code, message, details));
    }
}
