package com.yuksabal.hub.inventory.application.dto;

import com.yuksabal.hub.inventory.domain.Inventory;
import com.yuksabal.hub.inventory.domain.InventoryChangeType;
import com.yuksabal.hub.inventory.domain.InventoryLog;

import java.time.LocalDateTime;
import java.util.UUID;

// 재고 변경 이력 조회 결과 (서비스 출력, #284)
// 허브로 검색하면 여러 재고의 로그가 섞여 어떤 재고인지 알 수 있게 허브·상품을 함께 담는다
public record InventoryLogResult(
        UUID logId,
        UUID inventoryId,
        UUID hubId,
        UUID productId,
        InventoryChangeType changeType,
        Integer quantity,
        UUID orderId,
        LocalDateTime createdAt,
        UUID createdBy
) {
    // 재고는 조회할 때 함께 불러온다(@EntityGraph). 트랜잭션 안에서 바꿔 추가 쿼리가 나가지 않는다
    public static InventoryLogResult from(InventoryLog log) {
        Inventory inventory = log.getInventory();
        return new InventoryLogResult(
                log.getId(),
                inventory.getId(),
                inventory.getHubId(),
                inventory.getProductId(),
                log.getChangeType(),
                log.getQuantity(),
                log.getOrderId(),
                log.getCreatedAt(),
                log.getCreatedBy());
    }
}
