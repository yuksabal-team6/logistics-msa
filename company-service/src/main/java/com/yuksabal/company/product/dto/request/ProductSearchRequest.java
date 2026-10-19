package com.yuksabal.company.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "상품 검색 조건")
public record ProductSearchRequest(
        @Schema(description = "상품명 (일부만 입력해도 검색). 생략 시 조건 없음", example = "고등어")
        @Size(max = 200, message = "상품명은 200자 이하로 입력해 주세요.")
        String productName,

        @Schema(description = "소속 업체 ID (정확히 일치). 생략 시 조건 없음", example = "30000000-0000-4000-8000-000000000001")
        UUID companyId,

        @Schema(description = "소속 업체의 관리 허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000004")
        UUID hubId,

        @Schema(description = "활성 여부 (true = 활성만, false = 비활성만). 생략 시 조건 없음", example = "true")
        Boolean active
) {
}
