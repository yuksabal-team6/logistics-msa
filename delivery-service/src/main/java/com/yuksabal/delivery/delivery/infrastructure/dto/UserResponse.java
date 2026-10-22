package com.yuksabal.delivery.delivery.infrastructure.dto;

// user 내부 API #8 응답 중 담당자 검증에 쓰는 필드만 받는다 (나머지 필드는 무시된다)
// role은 user의 enum을 복사하지 않도록 문자열로 받는다
public record UserResponse(
        boolean active,
        String role
) {
}