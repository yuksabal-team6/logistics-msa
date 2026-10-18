package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// 내부 API 9 배송 취소 요청. 배송이 없거나 이미 취소됐으면 200, 허브 출발 후면 409
public record DeliveryCancelRequest(
        UUID orderId
) {
}
