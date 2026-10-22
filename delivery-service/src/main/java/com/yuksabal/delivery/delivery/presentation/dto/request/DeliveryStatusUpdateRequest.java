package com.yuksabal.delivery.delivery.presentation.dto.request;

import com.yuksabal.delivery.delivery.domain.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 배송(허브→업체) 상태 변경. DELIVERING, COMPLETED만 가능하고 나머지는 409
public record DeliveryStatusUpdateRequest(
        @Schema(description = "변경할 배송 상태 (DELIVERING, COMPLETED)", example = "DELIVERING")
        @NotNull DeliveryStatus deliveryStatus
) {
}
