package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #3 재고 차감 응답
public record StockDecreaseResponse(
        UUID hubId,
        UUID productId,
        Integer remainingQuantity,
        UUID orderId
) {
}
