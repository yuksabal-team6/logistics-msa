package com.yuksabal.hub.route.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "허브 경로 검색 조건")
public record HubRouteSearchRequest(
        @Schema(description = "출발 허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000001")
        UUID departureHubId,

        @Schema(description = "도착 허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000004")
        UUID arrivalHubId
) {
}
