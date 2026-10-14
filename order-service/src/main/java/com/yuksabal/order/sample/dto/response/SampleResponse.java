package com.yuksabal.order.sample.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 응답 DTO: 엔티티를 그대로 내보내지 않고 필요한 필드만 담는다.
@Schema(description = "샘플 응답")
public record SampleResponse(
        @Schema(description = "샘플 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID id,

        @Schema(description = "이름", example = "테스트 상품")
        String name,

        @Schema(description = "수량", example = "3")
        Integer quantity
) {
}
