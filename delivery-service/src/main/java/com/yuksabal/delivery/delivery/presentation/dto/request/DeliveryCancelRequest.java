package com.yuksabal.delivery.delivery.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// 내부 API #9. 보상 때 타임아웃이면 deliveryId를 모를 수 있어 orderId로 받는다
public record DeliveryCancelRequest(
        @Schema(description = "주문 ID", example = "60000000-0000-4000-8000-000000000001")
        @NotNull UUID orderId
) {
}
