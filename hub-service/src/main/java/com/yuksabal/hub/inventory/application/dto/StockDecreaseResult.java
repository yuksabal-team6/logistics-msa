package com.yuksabal.hub.inventory.application.dto;

import com.yuksabal.hub.inventory.domain.Inventory;

import java.util.UUID;

// 주문 재고 차감 결과 (서비스 출력, #284). remainingQuantity는 차감 후 수량 (이미 처리된 주문이면 현재 수량)
public record StockDecreaseResult(
        UUID hubId,
        UUID productId,
        int remainingQuantity,
        UUID orderId
) {
    public static StockDecreaseResult of(Inventory inventory, UUID orderId) {
        return new StockDecreaseResult(
                inventory.getHubId(),
                inventory.getProductId(),
                inventory.getQuantity(),
                orderId);
    }
}
