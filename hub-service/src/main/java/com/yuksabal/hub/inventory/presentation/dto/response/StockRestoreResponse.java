package com.yuksabal.hub.inventory.presentation.dto.response;

import com.yuksabal.hub.inventory.application.dto.StockRestoreResult;
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

    // 엔티티 대신 서비스 결과로 만든다 (#284)
    public static StockRestoreResponse from(StockRestoreResult result) {
        return new StockRestoreResponse(
                result.hubId(),
                result.productId(),
                result.restoredQuantity(),
                result.orderId());
    }
}
