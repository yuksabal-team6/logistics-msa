package com.yuksabal.company.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// 상품명 길이는 p_products 컬럼과 맞추고, 설명(TEXT)은 너무 긴 입력을 막으려고 1000자로 제한한다
// 소속 업체는 경로(/companies/{companyId}/products)로 받는다
@Schema(description = "상품 생성 요청")
public record ProductCreateRequest(
        @Schema(description = "상품명", example = "냉동 고등어 10kg")
        @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 200, message = "상품명은 200자 이하로 입력해 주세요.")
        String productName,

        @Schema(description = "상품 설명 (선택)", example = "국내산 냉동 고등어, 10kg 박스")
        @Size(max = 1000, message = "상품 설명은 1000자 이하로 입력해 주세요.")
        String description,

        // 엔티티는 null이면 0원으로 저장하지만, 가격을 빠뜨려 0원 상품이 생기지 않도록 필수로 받는다
        @Schema(description = "단가 (원 단위 정수, 0 이상)", example = "35000")
        @NotNull(message = "단가는 필수입니다.")
        @PositiveOrZero(message = "단가는 0 이상이어야 합니다.")
        Long unitPrice
) {
}
