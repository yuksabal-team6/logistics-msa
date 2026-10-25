package com.yuksabal.hub.inventory.presentation.dto.response;

import com.yuksabal.hub.inventory.application.dto.InventoryResult;
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

    // 엔티티 대신 서비스 결과로 만든다 (#284)
    public static InventoryResponse from(InventoryResult result) {
        return new InventoryResponse(
                result.inventoryId(),
                result.hubId(),
                result.productId(),
                result.quantity());
    }
}
