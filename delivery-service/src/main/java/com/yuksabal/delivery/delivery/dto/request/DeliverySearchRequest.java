package com.yuksabal.delivery.delivery.dto.request;

import com.yuksabal.delivery.delivery.entity.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "배송 검색 조건")
public record DeliverySearchRequest(
        @Schema(description = "배송 상태. 생략 시 조건 없음", example = "READY")
        DeliveryStatus deliveryStatus,

        @Schema(description = "출발 허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000004")
        UUID departureHubId,

        @Schema(description = "도착 허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000001")
        UUID destinationHubId,

        @Schema(description = "배송(허브→업체) 담당자 또는 경로 기록(허브→허브) 담당자. 생략 시 조건 없음",
                example = "10000000-0000-4000-8000-000000000003")
        UUID deliveryManagerId
) {
}
