package com.yuksabal.delivery.delivery.infrastructure.dto;

import java.math.BigDecimal;
import java.util.UUID;

// hub-service #6 응답 중 배송 경로 기록에 필요한 필드만
public record HubRouteResponse(
        UUID hubRouteId,
        UUID departureHubId,
        UUID arrivalHubId,
        BigDecimal distanceKm,
        Integer durationMinutes
) {
}
