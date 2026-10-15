package com.yuksabal.company.product.dto.response;

import com.yuksabal.company.product.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 내부 API #1 응답. order와의 계약이라 필드 이름·구성을 바꾸면 order client/dto/ProductResponse도 같이 수정해야 한다
// 출발 허브는 order가 #2(공급 업체 조회)로 얻으므로 hubId는 담지 않는다
@Schema(description = "상품 정보 (내부 API)")
public record ProductInternalResponse(
        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "상품명", example = "냉동 고등어 10kg")
        String productName,

        @Schema(description = "단가 (원 단위 정수)", example = "35000")
        Long unitPrice,

        @Schema(description = "공급 업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID companyId
) {

    public static ProductInternalResponse from(Product product) {
        return new ProductInternalResponse(
                product.getId(),
                product.getProductName(),
                product.getUnitPrice(),
                product.getCompany().getId()
        );
    }
}
