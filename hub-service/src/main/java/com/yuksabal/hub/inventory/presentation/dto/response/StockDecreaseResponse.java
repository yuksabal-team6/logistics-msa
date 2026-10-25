package com.yuksabal.hub.inventory.presentation.dto.response;

import com.yuksabal.hub.inventory.application.dto.StockDecreaseResult;
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

    // 엔티티 대신 서비스 결과로 만든다 (#284)
    public static StockDecreaseResponse from(StockDecreaseResult result) {
        return new StockDecreaseResponse(
                result.hubId(),
                result.productId(),
                result.remainingQuantity(),
                result.orderId());
    }
}
