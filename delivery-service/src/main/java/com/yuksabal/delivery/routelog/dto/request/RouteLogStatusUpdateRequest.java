package com.yuksabal.delivery.routelog.dto.request;

import com.yuksabal.delivery.routelog.entity.RouteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// 경로 기록(허브→허브) 상태 변경. IN_TRANSIT, ARRIVED만 가능하고 나머지는 409
// actualDistanceKm는 404 → 403 → 409 다음에 확인해야 해서 Bean Validation이 아니라 엔티티에서 검증한다
public record RouteLogStatusUpdateRequest(
        @Schema(description = "변경할 경로 기록 상태 (IN_TRANSIT, ARRIVED)", example = "ARRIVED")
        @NotNull RouteStatus routeStatus,

        @Schema(description = "실제 이동 거리(km). ARRIVED일 때 필수, 0보다 커야 함", example = "450.12")
        BigDecimal actualDistanceKm
) {
}
