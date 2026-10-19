package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #1 상품 조회 응답. companyId = 공급 업체 (주문의 supplierCompanyId와 일치해야 함)
public record ProductResponse(
        UUID productId,
        String productName,
        Long unitPrice,
        UUID companyId,
        // 상품과 업체가 모두 활성일 때만 true (company #147). false면 새 주문 불가
        boolean active
) {
}
