package com.yuksabal.delivery.delivery.dto.request;

import com.yuksabal.delivery.delivery.entity.Delivery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DeliveryCreateRequest(
        @Schema(description = "주문 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotNull UUID orderId,

        @Schema(description = "출발 허브 ID", example = "00000000-0000-0000-0000-000000000004")
        @NotNull UUID departureHubId,

        @Schema(description = "도착 허브 ID", example = "00000000-0000-0000-0000-000000000001")
        @NotNull UUID destinationHubId,

        @Schema(description = "수령 업체 ID")
        @NotNull UUID destinationCompanyId,

        @Schema(description = "배송지 주소", example = "서울특별시 송파구 송파대로 55")
        @NotBlank @Size(max = 500) String address,

        @Schema(description = "수령인", example = "박현아")
        @NotBlank @Size(max = 100) String recipientName,

        @Schema(description = "수령인 Slack ID (선택)", example = "sparta")
        @Size(max = 100) String recipientSlackId
) {

    public Delivery toEntity(){
        return Delivery.builder()
                .orderId(orderId)
                .departureHubId(departureHubId)
                .destinationHubId(destinationHubId)
                .destinationCompanyId(destinationCompanyId)
                .address(address)
                .recipientName(recipientName)
                .recipientSlackId(recipientSlackId)
                .build();
    }
}
