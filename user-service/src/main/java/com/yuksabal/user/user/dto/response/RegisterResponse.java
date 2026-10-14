package com.yuksabal.user.user.dto.response;

import com.yuksabal.user.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// API 명세 회원가입 성공 응답: userId, username, role
@Schema(description = "회원가입 응답")
public record RegisterResponse(
        @Schema(description = "회원 ID", example = "10000000-0000-4000-8000-000000000001")
        UUID userId,
        @Schema(description = "로그인 아이디", example = "user01")
        String username,
        @Schema(description = "역할", example = "USER")
        String role
) {

    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getUsername(), user.getRole().name());
    }
}
