package com.yuksabal.hub.inventory.application.dto;

import com.yuksabal.hub.inventory.domain.InventoryChangeType;

import java.util.UUID;

// 재고 변경 이력 검색 조건 (서비스 입력, #284). 조건은 모두 선택이고 함께 쓰면 AND
public record InventoryLogSearchCondition(
        UUID inventoryId,
        UUID hubId,
        UUID orderId,
        InventoryChangeType changeType
) {
}
