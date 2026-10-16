package com.yuksabal.delivery.routelog.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// 경로 기록(허브→허브) 담당자 지정. 활성 DELIVERY 회원만 가능
public record RouteLogManagerAssignRequest(
        @Schema(description = "지정할 허브 간 이동 담당자 회원 ID (활성 DELIVERY 회원)")
        @NotNull UUID deliveryManagerId
) {
}