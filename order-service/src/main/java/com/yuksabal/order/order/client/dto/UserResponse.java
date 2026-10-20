package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// 내부 API 8 회원 조회 응답 중 order가 쓰는 필드만. 나머지 필드는 무시됨
public record UserResponse(
        UUID userId,
        boolean active,
        // Slack 멤버 ID. 주문 접수 알림에서 주문자 멘션(<@slackId>)에 사용
        String slackId
) {
}
