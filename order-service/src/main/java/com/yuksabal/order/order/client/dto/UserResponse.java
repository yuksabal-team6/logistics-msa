package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// 내부 API 8 회원 조회 응답 중 order가 쓰는 필드만. 나머지 필드는 무시됨
// TODO 확인: slackId 필드가 추가되면 여기에도 추가해서 Slack 멘션에 사용
public record UserResponse(
        UUID userId,
        boolean active
) {
}
