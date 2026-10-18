package com.yuksabal.company.product.dto.response;

import com.yuksabal.company.product.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 내부 API #1 응답. order·hub와의 계약이라 필드 이름·구성을 바꾸면 호출하는 쪽 DTO도 같이 수정해야 한다
// hubId는 hub가 재고 행을 만들 때 상품의 업체가 요청 허브 소속인지 확인하는 용도 (order는 출발 허브를 #2로 얻는다)
@Schema(description = "상품 정보 (내부 API)")
public record ProductInternalResponse(
        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "상품명", example = "냉동 고등어 10kg")
        String productName,

        @Schema(description = "단가 (원 단위 정수)", example = "35000")
        Long unitPrice,

        @Schema(description = "공급 업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID companyId,

        @Schema(description = "공급 업체의 관리 허브 ID", example = "00000000-0000-0000-0000-000000000004")
        UUID hubId
) {

    public static ProductInternalResponse from(Product product) {
        return new ProductInternalResponse(
                product.getId(),
                product.getProductName(),
                product.getUnitPrice(),
                product.getCompany().getId(),
                product.getCompany().getHubId()
        );
    }
}
