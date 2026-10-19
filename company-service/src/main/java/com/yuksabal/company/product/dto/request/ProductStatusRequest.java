package com.yuksabal.company.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 상품 활성화(true)·비활성화(false). 삭제와 달리 다시 활성화할 수 있다
@Schema(description = "상품 상태 변경 요청")
public record ProductStatusRequest(
        @Schema(description = "활성 여부 (true = 활성화, false = 비활성화)", example = "false")
        @NotNull(message = "활성 여부는 필수입니다.")
        Boolean active
) {
}
