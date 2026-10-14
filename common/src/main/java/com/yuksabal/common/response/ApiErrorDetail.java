package com.yuksabal.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "필드별 상세 사유")
public record ApiErrorDetail(
        @Schema(description = "필드명", example = "productId")
        String field,
        @Schema(description = "사유", example = "존재하지 않거나 삭제된 상품입니다.")
        String reason
) {
}
