package com.yuksabal.user.user.dto.response;

import com.yuksabal.user.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 본인 정보. 비밀번호(해시)는 담지 않는다.
@Schema(description = "내 정보 응답")
public record MeResponse(
        @Schema(description = "회원 ID", example = "10000000-0000-4000-8000-000000000001")
        UUID userId,
        @Schema(description = "로그인 아이디", example = "user01")
        String username,
        @Schema(description = "이메일", example = "user01@example.com")
        String email,
        @Schema(description = "Slack ID", example = "U12345678")
        String slackId,
        @Schema(description = "역할", example = "USER")
        String role,
        @Schema(description = "소속 허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,
        @Schema(description = "소속 업체 ID", example = "20000000-0000-4000-8000-000000000001")
        UUID companyId,
        @Schema(description = "정보 공개 여부", example = "true")
        boolean isPublic
) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getUsername(), user.getEmail(), user.getSlackId(),
                user.getRole().name(), user.getHubId(), user.getCompanyId(), user.isPublic());
    }
}
