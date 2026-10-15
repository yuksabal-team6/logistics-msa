package com.yuksabal.order.order.dto.response;

import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// 주문 상세·목록·취소 응답 공용. 생성 응답 필드 + 금액·요청사항·보상 상태·생성일시
@Schema(description = "주문 응답")
public record OrderResponse(
        @Schema(description = "주문 ID", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId,

        @Schema(description = "주문 번호 (생성 규칙 미정)")
        String orderNumber,

        @Schema(description = "배송 ID (배송 생성 전·실패 시 null)", example = "70000000-0000-4000-8000-000000000001")
        UUID deliveryId,

        @Schema(description = "요청(수령) 업체 ID", example = "30000000-0000-4000-8000-000000000002")
        UUID requestCompanyId,

        @Schema(description = "공급 업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID supplierCompanyId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "주문 수량", example = "5")
        Integer quantity,

        @Schema(description = "주문 상태", example = "CONFIRMED")
        OrderStatus status,

        @Schema(description = "보상 상태", example = "NOT_REQUIRED")
        CompensationStatus compensationStatus,

        @Schema(description = "주문 시점 단가 (원)", example = "12000")
        Long unitPrice,

        @Schema(description = "총 금액 (원)", example = "60000")
        Long totalAmount,

        @Schema(description = "요청사항", example = "신선도 유지 요청")
        String requestMessage,

        @Schema(description = "생성일시", example = "2026-10-15T15:30:00")
        LocalDateTime createdAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getDeliveryId(),
                order.getRequestCompanyId(),
                order.getSupplierCompanyId(),
                order.getProductId(),
                order.getQuantity(),
                order.getStatus(),
                order.getCompensationStatus(),
                order.getUnitPrice(),
                order.getTotalAmount(),
                order.getRequestMessage(),
                order.getCreatedAt()
        );
    }
}
