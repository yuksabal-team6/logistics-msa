package com.yuksabal.delivery.delivery.dto.response;

import com.yuksabal.delivery.delivery.entity.Delivery;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record DeliveryCreateResponse(
        @Schema(description = "배송 ID") UUID deliveryId
){
    public static DeliveryCreateResponse from(Delivery delivery) {
        return new DeliveryCreateResponse(delivery.getId());
    }


}
