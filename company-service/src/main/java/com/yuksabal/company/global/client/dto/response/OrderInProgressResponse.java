package com.yuksabal.company.global.client.dto.response;

// order 진행 중 주문 확인 응답 (#143). 삭제·허브 변경 가능 여부 판단에 hasInProgressOrder만 쓴다 (나머지 필드는 무시된다)
// 진행 중 = PENDING, CONFIRMED, 보상 미완료 (회원 기준 /internal/orders/in-progress와 같은 기준)
// Boolean: 응답에 필드가 없으면 null로 받아 "확인하지 못함"(502)으로 막는다. boolean이면 false로 채워져 확인 없이 통과한다
public record OrderInProgressResponse(
        Boolean hasInProgressOrder
) {
}
