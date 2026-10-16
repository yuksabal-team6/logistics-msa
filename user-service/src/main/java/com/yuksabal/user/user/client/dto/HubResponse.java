package com.yuksabal.user.user.client.dto;

import java.util.UUID;

// #7 허브 존재 확인 응답 (hub-service HubInternalResponse)
public record HubResponse(
        UUID hubId
) {
}
