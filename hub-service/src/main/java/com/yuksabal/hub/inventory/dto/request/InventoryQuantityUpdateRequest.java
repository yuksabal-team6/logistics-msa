package com.yuksabal.hub.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
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

    // 요청만 보고 판단할 수 있어 DTO에서 검증한다. 검증 실패 시 details의 field는 "quantityChanged"
    // 둘 중 하나가 null이면 @NotNull이 알려 주므로 여기서는 통과시킨다
    @Schema(hidden = true)
    @AssertTrue(message = "조정할 재고 수량이 조회한 수량과 같습니다.")
    public boolean isQuantityChanged() {
        return expectedQuantity == null || quantity == null || !expectedQuantity.equals(quantity);
    }
}
