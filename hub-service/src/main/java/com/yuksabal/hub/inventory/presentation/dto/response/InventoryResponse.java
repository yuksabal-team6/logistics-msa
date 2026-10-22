package com.yuksabal.hub.inventory.presentation.dto.response;

import com.yuksabal.hub.inventory.domain.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 상품명은 company-service에 있어 담지 않는다 (목록마다 Feign 호출이 생기므로)
@Schema(description = "재고 응답")
public record InventoryResponse(
        @Schema(description = "재고 ID", example = "50000000-0000-4000-8000-000000000001")
        UUID inventoryId,

        @Schema(description = "허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "재고 수량", example = "100")
        Integer quantity
) {

    // hub는 LAZY 프록시지만 id만 꺼내므로 허브 조회 쿼리가 나가지 않는다
    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getHub().getId(),
                inventory.getProductId(),
                inventory.getQuantity());
    }
}
