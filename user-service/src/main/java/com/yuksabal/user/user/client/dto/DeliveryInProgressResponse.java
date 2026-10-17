package com.yuksabal.user.user.client.dto;

import java.util.UUID;

// delivery 내부 API 진행 중 배송 존재 여부 응답. delivery의 DeliveryInProgressResponse와 같은 필드 (계약)
public record DeliveryInProgressResponse(
        UUID deliveryManagerId,
        boolean hasInProgressDelivery
) {
}
