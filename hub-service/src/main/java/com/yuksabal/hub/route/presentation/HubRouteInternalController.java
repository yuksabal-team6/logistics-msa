package com.yuksabal.hub.route.presentation;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.route.presentation.dto.response.HubRouteResponse;
import com.yuksabal.hub.route.application.HubRouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 게이트웨이에 라우팅하지 않는 Feign 전용 API (delivery-service HubClient가 호출)
@Tag(name = "Hub Route Internal", description = "허브 경로 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/hub-routes")
public class HubRouteInternalController {

    private final HubRouteService hubRouteService;

    // ?from={출발 허브 ID}&to={도착 허브 ID}
    @Operation(summary = "허브 간 경로 조회",
            description = "두 허브 사이 경로를 한 건을 조회한다. 경로가 없으면 404.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping
    public ResponseEntity<ApiResponse<HubRouteResponse>> getHubRoute(
            @Parameter(description = "출발 허브 ID", example = "00000000-0000-0000-0000-000000000001")
            @RequestParam UUID from,
            @Parameter(description = "도착 허브 ID", example = "00000000-0000-0000-0000-000000000004")
            @RequestParam UUID to) {
        return ResponseEntity.ok(ApiResponse.success(hubRouteService.getHubRoute(from, to)));
    }
}
