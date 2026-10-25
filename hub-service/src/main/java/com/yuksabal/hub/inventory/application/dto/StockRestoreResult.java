package com.yuksabal.hub.inventory.application.dto;

import com.yuksabal.hub.inventory.domain.Inventory;

import java.util.UUID;

// 주문 재고 복원 결과 (서비스 출력, #284). restoredQuantity는 실제로 되돌린 수량 (차감 기록이 없었으면 0)
public record StockRestoreResult(
        UUID hubId,
        UUID productId,
        int restoredQuantity,
        UUID orderId
) {
    public static StockRestoreResult of(Inventory inventory, int restoredQuantity, UUID orderId) {
        return new StockRestoreResult(
                inventory.getHubId(),
                inventory.getProductId(),
                restoredQuantity,
                orderId);
    }
}
