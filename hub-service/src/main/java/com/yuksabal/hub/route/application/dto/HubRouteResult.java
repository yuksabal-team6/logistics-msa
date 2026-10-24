package com.yuksabal.hub.route.application.dto;

import com.yuksabal.hub.route.domain.HubRoute;

import java.math.BigDecimal;
import java.util.UUID;

// 허브 경로 조회 결과 (서비스 출력). 엔티티 대신 값만 담아 내보내고, API 모양은 컨트롤러가 응답 DTO로 정한다 (#284)
// 트랜잭션 안에서 값을 다 꺼내 담으므로 트랜잭션 밖(컨트롤러)에서 지연 로딩이 일어나지 않는다
public record HubRouteResult(
        UUID hubRouteId,
        UUID departureHubId,
        UUID arrivalHubId,
        BigDecimal distanceKm,
        Integer durationMinutes
) {
    public static HubRouteResult from(HubRoute hubRoute) {
        return new HubRouteResult(
                hubRoute.getId(),
                hubRoute.getDepartureHubId(),
                hubRoute.getArrivalHubId(),
                hubRoute.getDistanceKm(),
                hubRoute.getDurationMinutes());
    }
}
