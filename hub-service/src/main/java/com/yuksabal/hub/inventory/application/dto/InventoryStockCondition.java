package com.yuksabal.hub.inventory.application.dto;

import java.util.UUID;

// 재고 보유 여부 조회 조건 (서비스 입력, #284). productId, companyId 중 하나만 들어온다 (요청 DTO에서 검증)
public record InventoryStockCondition(
        UUID productId,
        UUID companyId
) {
}
