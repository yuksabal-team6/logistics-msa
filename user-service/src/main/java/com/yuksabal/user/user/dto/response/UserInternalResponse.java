package com.yuksabal.user.user.dto.response;

import com.yuksabal.user.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// company·delivery-service가 업무 권한·소속을, order-service가 주문자 상태(탈퇴 404·비활성 거절)를 검증할 때 쓰는 내부 응답 (#76, 내부 API #8).
// 공개 응답(UserResponse)과 분리해 필요한 필드만 둔다. 역할·소속·활성 여부의 판단은 호출 측이 한다.
// 필드는 명세 #8(userId, username, role, hubId, companyId) + active.
// 비밀번호(해시)·이메일 등 개인 정보는 담지 않는다. 필요해지면 필드를 추가한다 (호출 측에 영향 없음)
@Schema(description = "회원 내부 응답")
public record UserInternalResponse(
        @Schema(description = "회원 ID", example = "10000000-0000-4000-8000-000000000001")
        UUID userId,
        @Schema(description = "로그인 아이디", example = "deliver01")
        String username,
        @Schema(description = "계정 활성 여부 (false면 비활성 계정)", example = "true")
        boolean active,
        @Schema(description = "역할 (MASTER, USER, DELIVERY, COMPANY)", example = "DELIVERY")
        String role,
        @Schema(description = "소속 허브 ID (없으면 null)", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,
        @Schema(description = "소속 업체 ID (없으면 null)", example = "20000000-0000-4000-8000-000000000001")
        UUID companyId
) {

    public static UserInternalResponse from(User user) {
        return new UserInternalResponse(user.getId(), user.getUsername(), user.isActive(), user.getRole().name(),
                user.getHubId(), user.getCompanyId());
    }
}
