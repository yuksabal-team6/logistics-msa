package com.yuksabal.hub.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

// 주문 재고 차감·복원 공통 요청 (order-service HubClient #3, #4의 StockRequest와 같은 형식)
// hubId는 출발 허브(공급 업체의 관리 허브)
@Schema(description = "주문 재고 차감·복원 요청")
public record StockRequest(
        @Schema(description = "출발 허브 ID (공급 업체 관리 허브)", example = "00000000-0000-0000-0000-000000000001")
        @NotNull(message = "허브 ID는 필수입니다.")
        UUID hubId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        @NotNull(message = "상품 ID는 필수입니다.")
        UUID productId,

        @Schema(description = "주문 수량", example = "3")
        @NotNull(message = "수량은 필수입니다.")
        @Positive(message = "수량은 1 이상이어야 합니다.")
        Integer quantity,

        @Schema(description = "주문 ID (멱등성 키)", example = "60000000-0000-4000-8000-000000000001")
        @NotNull(message = "주문 ID는 필수입니다.")
        UUID orderId
) {
}
