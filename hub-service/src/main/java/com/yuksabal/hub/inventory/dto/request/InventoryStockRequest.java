package com.yuksabal.hub.inventory.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;

import java.util.UUID;

// company-service 상품·업체 삭제 전 재고 확인 (#137). 상품 기준 또는 업체 기준 중 하나로만 조회한다
@Schema(description = "재고 보유 여부 조회 조건 (productId, companyId 중 하나만)")
public record InventoryStockRequest(
        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID companyId
) {

    // 요청만 보고 판단할 수 있어 DTO에서 검증한다. 검증 실패 시 details의 field는 "singleTarget"
    @Schema(hidden = true)
    @AssertTrue(message = "productId와 companyId 중 하나만 입력해야 합니다.")
    public boolean isSingleTarget() {
        return (productId == null) != (companyId == null);
    }
}
