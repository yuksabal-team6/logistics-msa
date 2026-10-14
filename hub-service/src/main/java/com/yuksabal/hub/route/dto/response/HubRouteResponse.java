package com.yuksabal.hub.route.dto.response;

import com.yuksabal.hub.route.domain.HubRoute;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

// delivery-service HubClient가 받는 HubRouteResponse와 필드를 맞춘다
@Schema(description = "허브 경로 응답")
@Builder
public record HubRouteResponse(
        @Schema(description = "허브 경로 ID", example = "00000000-0000-0000-0001-000000000004")
        UUID hubRouteId,

        @Schema(description = "출발 허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID departureHubId,

        @Schema(description = "도착 허브 ID", example = "00000000-0000-0000-0000-000000000004")
        UUID arrivalHubId,

        @Schema(description = "거리(km)", example = "407.98")
        BigDecimal distanceKm,

        @Schema(description = "소요 시간(분)", example = "408")
        Integer durationMinutes
) {

    public static HubRouteResponse from(HubRoute hubRoute) {
        return HubRouteResponse.builder()
                .hubRouteId(hubRoute.getId())
                .departureHubId(hubRoute.getDepartureHub().getId())
                .arrivalHubId(hubRoute.getArrivalHub().getId())
                .distanceKm(hubRoute.getDistanceKm())
                .durationMinutes(hubRoute.getDurationMinutes())
                .build();
    }
}
