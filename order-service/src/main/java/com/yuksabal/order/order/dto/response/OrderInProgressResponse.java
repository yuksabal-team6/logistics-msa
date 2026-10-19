package com.yuksabal.order.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 진행 중 주문 존재 여부 내부 API 응답. user(회원 탈퇴), company(상품, 업체 삭제)와의 계약이라 필드를 바꾸면 호출하는 쪽 DTO도 같이 수정
// 요청에 보낸 파라미터 하나만 값이 있고 나머지는 null
@Schema(description = "진행 중 주문 존재 여부 응답 (내부 API)")
public record OrderInProgressResponse(
        @Schema(description = "회원 ID (주문자). ordererId로 물었을 때만", example = "10000000-0000-4000-8000-000000000001", nullable = true)
        UUID ordererId,

        @Schema(description = "상품 ID. productId로 물었을 때만", example = "40000000-0000-4000-8000-000000000001", nullable = true)
        UUID productId,

        @Schema(description = "업체 ID (공급 업체 또는 요청 업체). companyId로 물었을 때만", example = "30000000-0000-4000-8000-000000000001", nullable = true)
        UUID companyId,

        @Schema(description = "진행 중 주문 존재 여부 (true면 탈퇴, 삭제 제한)", example = "true")
        boolean hasInProgressOrder
) {

    public static OrderInProgressResponse ofOrderer(UUID ordererId, boolean exists) {
        return new OrderInProgressResponse(ordererId, null, null, exists);
    }

    public static OrderInProgressResponse ofProduct(UUID productId, boolean exists) {
        return new OrderInProgressResponse(null, productId, null, exists);
    }

    public static OrderInProgressResponse ofCompany(UUID companyId, boolean exists) {
        return new OrderInProgressResponse(null, null, companyId, exists);
    }
}
