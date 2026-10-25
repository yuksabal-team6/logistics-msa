package com.yuksabal.hub.hub.application.dto;

import com.yuksabal.hub.hub.domain.Hub;

import java.math.BigDecimal;
import java.util.UUID;

// 허브 조회 결과 (서비스 출력). 엔티티 대신 값만 담아 내보내고, API 모양은 컨트롤러가 응답 DTO로 정한다 (#284)
// 공개 상세(HubResponse)와 내부 API(HubInternalResponse)가 같은 결과에서 필요한 필드만 골라 쓴다
public record HubResult(
        UUID hubId,
        String hubName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude
) {
    public static HubResult from(Hub hub) {
        return new HubResult(hub.getId(), hub.getHubName(), hub.getAddress(), hub.getLatitude(), hub.getLongitude());
    }
}
