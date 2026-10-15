package com.yuksabal.delivery.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// 내부 API #9. 보상 때 타임아웃이면 deliveryId를 모를 수 있어 orderId로 받는다
public record DeliveryCancelRequest(
        @Schema(description = "주문 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotNull UUID orderId
) {
}
