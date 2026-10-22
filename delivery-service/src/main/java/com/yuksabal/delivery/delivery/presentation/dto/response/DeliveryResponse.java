package com.yuksabal.delivery.delivery.presentation.dto.response;

import com.yuksabal.delivery.delivery.domain.Delivery;
import com.yuksabal.delivery.delivery.domain.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "배송 응답")
@Builder
public record DeliveryResponse(
        @Schema(description = "배송 ID", example = "70000000-0000-4000-8000-000000000001")
        UUID deliveryId,

        @Schema(description = "주문 ID", example = "60000000-0000-4000-8000-000000000001")
        UUID orderId,

        @Schema(description = "배송 상태", example = "READY")
        DeliveryStatus deliveryStatus,

        @Schema(description = "출발 허브 ID", example = "00000000-0000-0000-0000-000000000004")
        UUID departureHubId,

        @Schema(description = "도착 허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID destinationHubId,

        @Schema(description = "도착(수령) 업체 ID", example = "30000000-0000-4000-8000-000000000002")
        UUID destinationCompanyId,

        @Schema(description = "배송(허브→업체) 담당자 ID. 미배정이면 null", example = "10000000-0000-4000-8000-000000000003")
        UUID deliveryManagerId,

        @Schema(description = "배송지 주소", example = "서울특별시 송파구 송파대로 55")
        String address,

        @Schema(description = "수령인 이름", example = "육사발")
        String recipientName,

        @Schema(description = "수령인 Slack ID", example = "U01ABCDEF2")
        String recipientSlackId,

        @Schema(description = "업체로 출발한 시각 (DELIVERING)", example = "2026-10-15T16:30:00")
        LocalDateTime startedAt,

        @Schema(description = "업체 도착 시각, 배송 완료 (COMPLETED)", example = "2026-10-15T17:10:00")
        LocalDateTime completedAt,

        @Schema(description = "생성 시각", example = "2026-10-15T10:00:00")
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
