package com.yuksabal.hub.inventory.presentation.dto.response;

import com.yuksabal.hub.inventory.application.dto.InventoryLogResult;
import com.yuksabal.hub.inventory.domain.InventoryChangeType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// 허브로 검색하면 여러 재고의 로그가 섞여 어떤 재고인지 알 수 있게 허브·상품을 함께 담는다
@Schema(description = "재고 변경 이력 응답")
public record InventoryLogResponse(
        @Schema(description = "재고 로그 ID", example = "70000000-0000-4000-8000-000000000001")
        UUID logId,

        @Schema(description = "재고 ID", example = "50000000-0000-4000-8000-000000000001")
        UUID inventoryId,

        @Schema(description = "허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "변경 유형", example = "DECREASE")
        InventoryChangeType changeType,

        @Schema(description = "변화량 (0 이상, 방향은 변경 유형으로 구분)", example = "3")
        Integer quantity,

        @Schema(description = "주문 ID. 입고·관리자 조정이면 null", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId,

        @Schema(description = "변경일시", example = "2026-10-18T15:30:00")
        LocalDateTime createdAt,

        @Schema(description = "변경한 사용자 ID", example = "10000000-0000-4000-8000-000000000001")
        UUID createdBy
) {

    // 엔티티 대신 서비스 결과로 만든다 (#284)
    public static InventoryLogResponse from(InventoryLogResult result) {
        return new InventoryLogResponse(
                result.logId(),
                result.inventoryId(),
                result.hubId(),
                result.productId(),
                result.changeType(),
                result.quantity(),
                result.orderId(),
                result.createdAt(),
                result.createdBy());
    }
}
