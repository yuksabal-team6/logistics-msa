package com.yuksabal.hub.inventory.dto.request;

import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 조건은 모두 선택이고 함께 쓰면 AND. 조건이 없으면 전체 로그
@Schema(description = "재고 변경 이력 검색 조건")
public record InventoryLogSearchRequest(
        @Schema(description = "재고 ID (정확히 일치). 생략 시 조건 없음", example = "50000000-0000-4000-8000-000000000001")
        UUID inventoryId,

        @Schema(description = "허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "주문 ID (정확히 일치). 생략 시 조건 없음", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId,

        @Schema(description = "변경 유형. 생략 시 조건 없음", example = "DECREASE")
        InventoryChangeType changeType
) {
}
