package com.yuksabal.order.sample.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 요청 DTO: 필드마다 검증 + @Schema. 검증 실패 시 message가 그대로 응답 message로 나간다.
@Schema(description = "샘플 생성 요청")
public record SampleCreateRequest(
        @Schema(description = "이름", example = "테스트 상품")
        @NotBlank(message = "이름은 필수입니다.")
        String name,

        @Schema(description = "수량", example = "3")
        @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        Integer quantity
) {
}
