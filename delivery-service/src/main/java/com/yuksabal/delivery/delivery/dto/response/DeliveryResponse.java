package com.yuksabal.delivery.delivery.dto.response;

import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.entity.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "배송 응답")
@Builder
public record DeliveryResponse(
        @Schema(description = "배송 ID")
        UUID deliveryId,

        @Schema(description = "주문 ID")
        UUID orderId,

        @Schema(description = "배송 상태", example = "READY")
        DeliveryStatus deliveryStatus,

        @Schema(description = "출발 허브 ID")
        UUID departureHubId,

        @Schema(description = "도착 허브 ID")
        UUID destinationHubId,

        @Schema(description = "도착(수령) 업체 ID")
        UUID destinationCompanyId,

        @Schema(description = "배송(허브→업체) 담당자 ID. 미배정이면 null")
        UUID deliveryManagerId,

        @Schema(description = "배송지 주소")
        String address,

        @Schema(description = "수령인 이름")
        String recipientName,

        @Schema(description = "수령인 Slack ID")
        String recipientSlackId,

        @Schema(description = "업체로 출발한 시각 (DELIVERING)")
        LocalDateTime startedAt,

        @Schema(description = "업체 도착 시각, 배송 완료 (COMPLETED)")
        LocalDateTime completedAt,

        @Schema(description = "생성 시각")
        LocalDateTime createdAt
) {

    public static DeliveryResponse from(Delivery delivery) {
        return DeliveryResponse.builder()
                .deliveryId(delivery.getId())
                .orderId(delivery.getOrderId())
                .deliveryStatus(delivery.getDeliveryStatus())
                .departureHubId(delivery.getDepartureHubId())
                .destinationHubId(delivery.getDestinationHubId())
                .destinationCompanyId(delivery.getDestinationCompanyId())
                .deliveryManagerId(delivery.getDeliveryManagerId())
                .address(delivery.getAddress())
                .recipientName(delivery.getRecipientName())
                .recipientSlackId(delivery.getRecipientSlackId())
                .startedAt(delivery.getStartedAt())
                .completedAt(delivery.getCompletedAt())
                .createdAt(delivery.getCreatedAt())
                .build();
    }
}
