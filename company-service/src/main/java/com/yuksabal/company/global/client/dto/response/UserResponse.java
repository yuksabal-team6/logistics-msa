package com.yuksabal.company.global.client.dto.response;

import java.util.UUID;

// user 내부 API #8 응답 중 업체 관리자 검증에 쓰는 필드만 받는다 (나머지 필드는 무시된다)
public record UserResponse(
        boolean active,
        String role,
        UUID companyId
) {
}
