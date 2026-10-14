package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #3 재고 차감, #4 재고 복원 공통 요청. hubId = 출발 허브(공급 업체 허브)
public record StockRequest(
        UUID hubId,
        UUID productId,
        Integer quantity,
        UUID orderId
) {
}
