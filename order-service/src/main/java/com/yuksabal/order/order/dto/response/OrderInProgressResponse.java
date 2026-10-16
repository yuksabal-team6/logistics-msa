package com.yuksabal.order.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 진행 중 주문 존재 여부 내부 API 응답. user와의 계약이라 필드를 바꾸면 user 쪽 DTO도 같이 수정
@Schema(description = "진행 중 주문 존재 여부 응답 (내부 API)")
public record OrderInProgressResponse(
        @Schema(description = "회원 ID (주문자)", example = "10000000-0000-4000-8000-000000000001")
        UUID ordererId,

        @Schema(description = "진행 중 주문 존재 여부 (true면 탈퇴 제한)", example = "true")
        boolean hasInProgressOrder
) {
}
