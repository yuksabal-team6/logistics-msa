package com.yuksabal.hub.inventory.dto.response;

import com.yuksabal.hub.inventory.entity.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// order-service HubClient #3 응답 (StockDecreaseResponse와 같은 형식)
@Schema(description = "주문 재고 차감 응답")
public record StockDecreaseResponse(
        @Schema(description = "허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "차감 후 남은 재고 수량 (이미 처리된 주문이면 현재 수량)", example = "97")
        Integer remainingQuantity,

        @Schema(description = "주문 ID", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId
) {

    // hub는 LAZY 프록시지만 id만 꺼내므로 허브 조회 쿼리가 나가지 않는다
    public static StockDecreaseResponse of(Inventory inventory, UUID orderId) {
        return new StockDecreaseResponse(
                inventory.getHub().getId(),
                inventory.getProductId(),
                inventory.getQuantity(),
                orderId);
    }
}
