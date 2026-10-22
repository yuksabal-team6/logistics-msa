package com.yuksabal.delivery.delivery.infrastructure.dto;

import java.util.UUID;

// order-service #10 응답. status는 order의 enum을 복사하지 않도록 문자열로 받는다
public record OrderCompleteResponse(
        UUID orderId,
        String status
) {
}