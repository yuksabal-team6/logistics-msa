package com.yuksabal.gateway.global.jwt;

import java.util.UUID;

// 검증된 JWT에서 꺼낸 사용자 정보 → X-User-Id, X-User-Role 헤더로 전달
public record AuthUser(UUID userId, UserRole role) {
}
