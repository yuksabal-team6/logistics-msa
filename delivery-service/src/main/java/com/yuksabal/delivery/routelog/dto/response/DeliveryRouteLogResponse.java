package com.yuksabal.delivery.routelog.dto.response;

import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.entity.RouteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "배송 경로 기록(허브→허브) 응답")
@Builder
public record DeliveryRouteLogResponse(
        @Schema(description = "경로 기록 ID")
        UUID routeLogId,

        @Schema(description = "배송 ID")
        UUID deliveryId,

        @Schema(description = "허브 이동 경로 ID")
        UUID hubRouteId,

        @Schema(description = "출발 허브 ID")
        UUID departureHubId,

        @Schema(description = "도착 허브 ID")
        UUID destinationHubId,

        @Schema(description = "경로 기록(허브→허브) 담당자 ID. 미배정이면 null")
        UUID deliveryManagerId,

        @Schema(description = "예상 거리(km)", example = "450.00")
        BigDecimal expectedDistanceKm,

        @Schema(description = "예상 소요 시간(분)", example = "300")
        Integer expectedDurationMinutes,

        @Schema(description = "실제 거리(km). 도착 전이면 null", example = "450.12")
        BigDecimal actualDistanceKm,

        @Schema(description = "실제 소요 시간(분). 출발·도착 시각 차이로 계산, 도착 전이면 null")
        Integer actualDurationMinutes,

        @Schema(description = "경로 기록 상태", example = "READY")
        RouteStatus routeStatus,

        @Schema(description = "허브 출발 시각 (IN_TRANSIT)")
        LocalDateTime departedAt,

        @Schema(description = "허브 도착 시각 (ARRIVED)")
        LocalDateTime arrivedAt,

        @Schema(description = "생성 시각")
        LocalDateTime createdAt
) {

    // 출발·도착 허브는 경로 기록에 컬럼이 없어 연결된 배송에서 가져온다
    public static DeliveryRouteLogResponse from(DeliveryRouteLog routeLog) {
        Delivery delivery = routeLog.getDelivery();
        return DeliveryRouteLogResponse.builder()
                .routeLogId(routeLog.getId())
                .deliveryId(delivery.getId())
                .hubRouteId(routeLog.getHubRouteId())
                .departureHubId(delivery.getDepartureHubId())
                .destinationHubId(delivery.getDestinationHubId())
                .deliveryManagerId(routeLog.getDeliveryManagerId())
                .expectedDistanceKm(routeLog.getExpectedDistanceKm())
                .expectedDurationMinutes(routeLog.getExpectedDurationMinutes())
                .actualDistanceKm(routeLog.getActualDistanceKm())
                .actualDurationMinutes(routeLog.getActualDurationMinutes())
                .routeStatus(routeLog.getRouteStatus())
                .departedAt(routeLog.getDepartedAt())
                .arrivedAt(routeLog.getArrivedAt())
                .createdAt(routeLog.getCreatedAt())
                .build();
    }
}
