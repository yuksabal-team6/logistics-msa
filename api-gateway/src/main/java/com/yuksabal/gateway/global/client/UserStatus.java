package com.yuksabal.gateway.global.client;

// user-service 내부 회원 조회(GET /internal/users/{userId}) 응답 중 인증에 필요한 값만 받는다
// role은 gateway UserRole에 없는 값이 와도 역할 불일치로 처리하도록 문자열로 둔다
public record UserStatus(boolean active, String role) {
}
