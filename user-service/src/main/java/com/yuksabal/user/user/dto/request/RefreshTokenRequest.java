package com.yuksabal.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 토큰 재발급·로그아웃 요청
@Schema(description = "Refresh Token 요청")
public record RefreshTokenRequest(

        @Schema(description = "로그인·재발급 응답으로 받은 Refresh Token", example = "q3Jk...")
        @NotBlank(message = "Refresh Token은 필수입니다.")
        String refreshToken
) {

    // 로그 등에 요청이 찍혀도 토큰 원문이 노출되지 않게 한다
    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=****]";
    }
}
