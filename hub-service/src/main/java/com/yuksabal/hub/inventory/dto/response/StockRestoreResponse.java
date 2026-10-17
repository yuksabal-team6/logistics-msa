package com.yuksabal.hub.inventory.dto.response;

import com.yuksabal.hub.inventory.entity.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// order-service HubClient #4 응답 (StockRestoreResponse와 같은 형식)
@Schema(description = "주문 재고 복원 응답")
public record StockRestoreResponse(
        @Schema(description = "허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "복원한 수량 (차감 기록이 없었으면 0)", example = "3")
        Integer restoredQuantity,

        @Schema(description = "주문 ID", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId
) {

    // hub는 LAZY 프록시지만 id만 꺼내므로 허브 조회 쿼리가 나가지 않는다
    public static StockRestoreResponse of(Inventory inventory, int restoredQuantity, UUID orderId) {
        return new StockRestoreResponse(
                inventory.getHub().getId(),
                inventory.getProductId(),
                restoredQuantity,
                orderId);
    }
}
