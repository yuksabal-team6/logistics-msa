package com.yuksabal.hub.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "재고 검색 조건")
public record InventorySearchRequest(
        @Schema(description = "허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "상품 ID (정확히 일치). 생략 시 조건 없음", example = "40000000-0000-4000-8000-000000000001")
        UUID productId
) {
}
