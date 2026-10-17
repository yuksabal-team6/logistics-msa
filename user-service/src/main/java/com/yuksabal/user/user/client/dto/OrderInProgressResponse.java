package com.yuksabal.user.user.client.dto;

import java.util.UUID;

// order 내부 API 진행 중 주문 존재 여부 응답. order의 OrderInProgressResponse와 같은 필드 (계약)
public record OrderInProgressResponse(
        UUID ordererId,
        boolean hasInProgressOrder
) {
}
