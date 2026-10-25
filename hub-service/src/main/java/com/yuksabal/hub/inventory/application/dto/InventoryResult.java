package com.yuksabal.hub.inventory.application.dto;

import com.yuksabal.hub.inventory.domain.Inventory;

import java.util.UUID;

// 재고 조회·변경 결과 (서비스 출력). 엔티티 대신 값만 담아 내보내고, API 모양은 컨트롤러가 응답 DTO로 정한다 (#284)
public record InventoryResult(
        UUID inventoryId,
        UUID hubId,
        UUID productId,
        int quantity
) {
    public static InventoryResult from(Inventory inventory) {
        return new InventoryResult(
                inventory.getId(),
                inventory.getHubId(),
                inventory.getProductId(),
                inventory.getQuantity());
    }
}
