package com.yuksabal.user.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

// 로그인·토큰 재발급 응답
@Schema(description = "로그인·토큰 재발급 응답")
public record LoginResponse(
        @Schema(description = "Access Token (Authorization: Bearer {accessToken})", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,
        @Schema(description = "토큰 타입", example = "Bearer")
        String tokenType,
        @Schema(description = "Access Token 만료까지 남은 시간(초)", example = "3600")
        long expiresIn,
        @Schema(description = "Refresh Token (POST /api/v1/auth/refresh로 재발급, 한 번 쓰면 새 토큰으로 바뀜)", example = "q3Jk...")
        String refreshToken,
        @Schema(description = "Refresh Token 만료까지 남은 시간(초)", example = "604800")
        long refreshExpiresIn
) {

    private static final String BEARER = "Bearer";

    public static LoginResponse bearer(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn) {
        return new LoginResponse(accessToken, BEARER, expiresIn, refreshToken, refreshExpiresIn);
    }

    // 로그 등에 응답이 찍혀도 토큰 원문이 노출되지 않게 한다
    @Override
    public String toString() {
        return "LoginResponse[accessToken=****, tokenType=" + tokenType + ", expiresIn=" + expiresIn
                + ", refreshToken=****, refreshExpiresIn=" + refreshExpiresIn + "]";
    }
}
