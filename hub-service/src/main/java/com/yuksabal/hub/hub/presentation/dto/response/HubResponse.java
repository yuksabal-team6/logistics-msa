package com.yuksabal.hub.hub.presentation.dto.response;

import com.yuksabal.hub.hub.domain.Hub;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "허브 응답")
public record HubResponse(
        @Schema(description = "허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId,

        @Schema(description = "허브명", example = "서울특별시 센터")
        String hubName,

        @Schema(description = "주소", example = "서울특별시 송파구 송파대로 55")
        String address,

        @Schema(description = "위도", example = "37.4779000")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.1246000")
        BigDecimal longitude
) {

    public static HubResponse from(Hub hub) {
        return new HubResponse(hub.getId(), hub.getHubName(), hub.getAddress(), hub.getLatitude(), hub.getLongitude());
    }
}
