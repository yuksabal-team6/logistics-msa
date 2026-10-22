package com.yuksabal.delivery.delivery.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// 배송(허브→업체) 담당자 지정. 활성 DELIVERY 회원만 가능
public record DeliveryManagerAssignRequest(
        @Schema(description = "지정할 배송(허브→업체) 담당자 회원 ID (활성 DELIVERY 회원)",
                example = "10000000-0000-4000-8000-000000000003")
        @NotNull UUID deliveryManagerId
) {
}