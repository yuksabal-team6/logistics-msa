package com.yuksabal.order.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// 명세 4.6 주문 생성 요청. 단가·금액은 서버가 상품 조회(#1)로 계산
@Schema(description = "주문 생성 요청")
public record OrderCreateRequest(
        @Schema(description = "요청(수령) 업체 ID", example = "30000000-0000-4000-8000-000000000002")
        @NotNull(message = "요청 업체 ID는 필수입니다.")
        UUID requestCompanyId,

        @Schema(description = "공급 업체 ID", example = "30000000-0000-4000-8000-000000000001")
        @NotNull(message = "공급 업체 ID는 필수입니다.")
        UUID supplierCompanyId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        @NotNull(message = "상품 ID는 필수입니다.")
        UUID productId,

        @Schema(description = "주문 수량 (1 이상)", example = "5")
        @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        Integer quantity,

        @Schema(description = "요청사항 (선택)", example = "신선도 유지 요청")
        String requestMessage,

        @Schema(description = "수령인", example = "홍길동")
        @NotBlank(message = "수령인은 필수입니다.")
        String recipientName,

        @Schema(description = "배송지 주소", example = "부산광역시 중구 중앙대로 2")
        @NotBlank(message = "배송지 주소는 필수입니다.")
        String deliveryAddress
) {
}
