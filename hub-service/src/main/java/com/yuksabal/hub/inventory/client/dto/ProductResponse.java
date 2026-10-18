package com.yuksabal.hub.inventory.client.dto;

import java.util.UUID;

// company-service 내부 API #1 상품 조회 응답 중 hub에서 쓰는 필드만 받는다 (나머지 필드는 무시된다)
// hubId는 상품 업체의 관리 허브. 재고 행을 만들 때 요청 허브가 맞는지 확인한다 (company #128)
public record ProductResponse(
        UUID productId,
        UUID companyId,
        UUID hubId
) {
}
