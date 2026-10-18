package com.yuksabal.hub.inventory.client.dto;

import java.util.UUID;

// company-service 내부 API #1 상품 조회 응답 중 hub에서 쓰는 필드만 받는다 (나머지 필드는 무시된다)
// hubId는 상품 업체의 관리 허브. company 쪽 응답에 hubId 추가가 합의되어야 채워진다
public record ProductResponse(
        UUID productId,
        UUID companyId,
        UUID hubId
) {
}
