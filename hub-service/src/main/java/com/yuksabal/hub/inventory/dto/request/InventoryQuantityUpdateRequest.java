package com.yuksabal.hub.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

// expectedQuantity는 조회할 때 본 수량. 현재 수량과 같을 때만 조정해 그 사이의 주문 차감·입고를 덮어쓰지 않는다
@Schema(description = "재고 수량 조정 요청")
public record InventoryQuantityUpdateRequest(
        @Schema(description = "조회할 때 본 재고 수량", example = "100")
        @NotNull(message = "조회한 재고 수량은 필수입니다.")
        @PositiveOrZero(message = "조회한 재고 수량은 0 이상이어야 합니다.")
        Integer expectedQuantity,

        @Schema(description = "조정할 재고 수량 (실사 결과)", example = "80")
        @NotNull(message = "조정할 재고 수량은 필수입니다.")
        @PositiveOrZero(message = "조정할 재고 수량은 0 이상이어야 합니다.")
        Integer quantity
) {
}
