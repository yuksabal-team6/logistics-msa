package com.yuksabal.hub.route.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.route.dto.request.HubRouteSearchRequest;
import com.yuksabal.hub.route.dto.response.HubRouteResponse;
import com.yuksabal.hub.route.service.HubRouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.*;

// 인증은 게이트웨이가 JWT로 처리한다. 로그인한 모든 역할이 조회할 수 있어 서비스에서 권한을 다시 확인하지 않는다.
@Tag(name = "Hub Route", description = "허브 경로 조회 외부 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/hub-routes")
public class HubRouteController {

    private final HubRouteService hubRouteService;

    // ?departureHubId=&arrivalHubId=&page=0&size=20&sort=distanceKm,asc (조건은 모두 선택)
    @Operation(summary = "허브 경로 조건부 목록 조회",
            description = "출발·도착 허브 ID로 조회한다. 같은 허브나 없는 허브 ID면 빈 목록. 정렬 가능: createdAt, distanceKm, durationMinutes")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED})
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<HubRouteResponse>>> getHubRoutes(
            @ParameterObject HubRouteSearchRequest request,
            @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(hubRouteService.getHubRoutes(request, pageable), "목록을 조회했습니다."));
    }

    @Operation(summary = "허브 경로 상세 조회", description = "경로 ID로 한 건을 조회한다. 경로가 없으면 404.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, RESOURCE_NOT_FOUND})
    @GetMapping("/{routeId}")
    public ResponseEntity<ApiResponse<HubRouteResponse>> getHubRoute(@PathVariable UUID routeId) {
        return ResponseEntity.ok(ApiResponse.success(hubRouteService.getHubRouteById(routeId), "허브 경로를 상세 조회했습니다."));
    }
}
