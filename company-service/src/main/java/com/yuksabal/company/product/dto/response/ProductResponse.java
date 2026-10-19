package com.yuksabal.company.product.dto.response;

import com.yuksabal.company.product.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// 상품 생성·조회·수정 응답 공용. 업체는 ID만 담는다(업체명 등을 꺼내면 LAZY 업체 조회 쿼리가 추가로 나감)
@Schema(description = "상품 응답")
public record ProductResponse(
        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "소속 업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID companyId,

        @Schema(description = "상품명", example = "냉동 고등어 10kg")
        String productName,

        @Schema(description = "상품 설명", example = "국내산 냉동 고등어, 10kg 박스")
        String description,

        @Schema(description = "단가 (원 단위 정수)", example = "35000")
        Long unitPrice,

        @Schema(description = "활성 여부. 비활성이면 새 주문·입고 불가", example = "true")
        boolean active,

        @Schema(description = "생성일시", example = "2026-10-15T15:30:00")
        LocalDateTime createdAt,

        @Schema(description = "수정일시", example = "2026-10-15T15:30:00")
        LocalDateTime updatedAt
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getCompany().getId(),
                product.getProductName(),
                product.getDescription(),
                product.getUnitPrice(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
