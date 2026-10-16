package com.yuksabal.order.order.dto.response;

import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 내부 API #10 응답. delivery와의 계약이라 필드를 바꾸면 delivery 쪽 DTO도 같이 수정
@Schema(description = "주문 완료 처리 응답 (내부 API)")
public record OrderCompleteResponse(
        @Schema(description = "주문 ID", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId,

        @Schema(description = "주문 상태", example = "COMPLETED")
        OrderStatus status
) {

    /**
     * Order 엔티티를 주문 완료 응답 DTO로 변환한다.
     *
     * @param order 변환할 주문 엔티티
     * @return 주문 ID, 상태를 포함한 응답 dto
     */
    public static OrderCompleteResponse from(Order order) {
        return new OrderCompleteResponse(order.getId(), order.getStatus());
    }
}
