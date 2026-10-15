package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #5 배송 생성 응답
public record DeliveryCreateResponse(
        UUID deliveryId
) {
}
