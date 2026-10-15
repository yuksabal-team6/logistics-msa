package com.yuksabal.user.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 응답")
public record LoginResponse(
        @Schema(description = "Access Token (Authorization: Bearer {accessToken})", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,
        @Schema(description = "토큰 타입", example = "Bearer")
        String tokenType,
        @Schema(description = "만료까지 남은 시간(초)", example = "3600")
        long expiresIn
) {

    private static final String BEARER = "Bearer";

    public static LoginResponse bearer(String accessToken, long expiresIn) {
        return new LoginResponse(accessToken, BEARER, expiresIn);
    }

    // 로그 등에 응답이 찍혀도 토큰 원문이 노출되지 않게 한다
    @Override
    public String toString() {
        return "LoginResponse[accessToken=****, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
