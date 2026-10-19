package com.yuksabal.company.global.client.dto.response;

// order 진행 중 주문 확인 응답 (#143에서 합의, order 구현 전). 삭제 가능 여부 판단에 hasInProgressOrder만 쓴다
// 진행 중 = PENDING, CONFIRMED, 보상 미완료 (회원 기준 /internal/orders/in-progress와 같은 기준)
public record OrderInProgressResponse(
        boolean hasInProgressOrder
) {
}
