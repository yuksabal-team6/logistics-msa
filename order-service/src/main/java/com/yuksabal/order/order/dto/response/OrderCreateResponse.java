package com.yuksabal.order.order.dto.response;

import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 명세 4.6 주문 생성 응답 + orderNumber(테이블 명세). status는 결정 #21 enum (명세의 CREATED 아님)
@Schema(description = "주문 생성 응답")
public record OrderCreateResponse(
        @Schema(description = "주문 ID", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId,

        @Schema(description = "주문 번호 (ORD-{yyyyMMdd}-{orderId 하이픈 뺀 앞 12자리 대문자}, 한국 시간)", example = "ORD-20261020-600000000000")
        String orderNumber,

        @Schema(description = "배송 ID", example = "70000000-0000-4000-8000-000000000001")
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
        OrderStatus status
) {

    public static OrderCreateResponse from(Order order) {
        return new OrderCreateResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getDeliveryId(),
                order.getRequestCompanyId(),
                order.getSupplierCompanyId(),
                order.getProductId(),
                order.getQuantity(),
                order.getStatus()
        );
    }
}
