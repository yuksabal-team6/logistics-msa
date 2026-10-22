package com.yuksabal.hub.inventory.application;

import java.util.UUID;

// 재고 서비스가 쓰는 상품 정보. company-service 응답 형식(ProductResponse)과 분리해, 응답이 바뀌어도 서비스는 그대로 둔다
// hubId는 상품 업체의 관리 허브 (재고는 이 허브에만 입고한다). active가 false면 입고하지 않는다
public record ProductInfo(
        UUID productId,
        UUID companyId,
        UUID hubId,
        boolean active
) {
}
