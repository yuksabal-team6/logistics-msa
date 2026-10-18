package com.yuksabal.hub.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

// hubId는 상품 업체의 관리 허브여야 한다 (상품 재고는 업체 관리 허브에만 둔다)
@Schema(description = "재고 입고 요청")
public record InventoryInboundRequest(
        @Schema(description = "입고할 허브 ID (상품 업체의 관리 허브)", example = "00000000-0000-0000-0000-000000000001")
        @NotNull(message = "허브 ID는 필수입니다.")
        UUID hubId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        @NotNull(message = "상품 ID는 필수입니다.")
        UUID productId,

        @Schema(description = "입고 수량", example = "50")
        @NotNull(message = "입고 수량은 필수입니다.")
        @Positive(message = "입고 수량은 1 이상이어야 합니다.")
        Integer quantity
) {
}
