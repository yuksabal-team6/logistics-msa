package com.yuksabal.delivery.delivery.presentation.dto.response;

import com.yuksabal.delivery.delivery.domain.Delivery;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record DeliveryCreateResponse(
        @Schema(description = "배송 ID", example = "70000000-0000-4000-8000-000000000001")
        UUID deliveryId
){
    public static DeliveryCreateResponse from(Delivery delivery) {
        return new DeliveryCreateResponse(delivery.getId());
    }


}
