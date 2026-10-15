package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #1 상품 조회 응답. companyId = 공급 업체 (주문의 supplierCompanyId와 일치해야 함)
public record ProductResponse(
        UUID productId,
        String name,
        Long unitPrice,
        UUID companyId
) {
}
