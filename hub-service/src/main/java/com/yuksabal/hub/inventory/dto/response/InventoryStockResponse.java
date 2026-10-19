package com.yuksabal.hub.inventory.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

// company-service 상품·업체 삭제 전 재고 확인 응답 (#137)
@Schema(description = "재고 보유 여부 응답")
public record InventoryStockResponse(
        @Schema(description = "남은 수량이 0보다 큰 재고가 있는지 (수량 0인 재고는 이력이라 false)", example = "true")
        boolean hasStock
) {
}
