package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #4 재고 복원 응답
public record StockRestoreResponse(
        UUID hubId,
        UUID productId,
        Integer restoredQuantity,
        UUID orderId
) {
}
