package com.yuksabal.hub.route.application.dto;

import java.util.UUID;

// 허브 경로 목록 검색 조건 (서비스 입력). 요청 DTO와 분리해 API 파라미터가 바뀌어도 서비스는 그대로다 (#284)
// 조건은 모두 선택이고, null이면 그 조건으로 거르지 않는다
public record HubRouteSearchCondition(
        UUID departureHubId,
        UUID arrivalHubId
) {
}
