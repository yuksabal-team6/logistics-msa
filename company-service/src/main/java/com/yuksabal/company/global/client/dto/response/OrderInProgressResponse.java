package com.yuksabal.company.global.client.dto.response;

// 계약 가정 (#137): order 진행 중 주문 확인 응답. 삭제 가능 여부 판단에 hasInProgressOrder만 쓴다
// 진행 중 = PENDING, CONFIRMED, 보상 미완료 (회원 기준 /internal/orders/in-progress와 같은 기준)
public record OrderInProgressResponse(
        boolean hasInProgressOrder
) {
}
