package com.yuksabal.delivery.routelog.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.delivery.global.swagger.ApiErrorCodes;
import com.yuksabal.delivery.routelog.dto.request.RouteLogManagerAssignRequest;
import com.yuksabal.delivery.routelog.dto.request.RouteLogStatusUpdateRequest;
import com.yuksabal.delivery.routelog.dto.response.DeliveryRouteLogResponse;
import com.yuksabal.delivery.routelog.service.DeliveryRouteLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_ACCESS_DENIED;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_CONFLICT;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;

@Tag(name = "DeliveryRouteLog", description = "배송 경로 기록(허브→허브) 외부 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/deliveries/{deliveryId}/routes")
public class DeliveryRouteLogController {

    private final DeliveryRouteLogService deliveryRouteLogService;

    @Operation(summary = "배송 경로 기록 목록 조회",
            description = "배송당 0~1건이라 페이징 없이 목록으로 응답한다. 같은 허브 배송이면 빈 목록. "
                    + "배송이 없으면 404. DELIVERY는 배송 담당자 또는 경로 기록 담당자가 아니면 403.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {DELIVERY_ACCESS_DENIED})
    @GetMapping
    public ResponseEntity<ApiResponse<List<DeliveryRouteLogResponse>>> getRouteLogs(
            @PathVariable UUID deliveryId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryRouteLogService.getRouteLogs(deliveryId, userId, UserRole.from(role)),
                "목록을 조회했습니다."));
    }

    @Operation(summary = "배송 경로 기록 상세 조회",
            description = "배송이나 경로 기록이 없거나 그 배송의 경로 기록이 아니면 404. "
                    + "DELIVERY는 배송 담당자 또는 경로 기록 담당자가 아니면 403.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {DELIVERY_ACCESS_DENIED})
    @GetMapping("/{routeLogId}")
    public ResponseEntity<ApiResponse<DeliveryRouteLogResponse>> getRouteLog(
            @PathVariable UUID deliveryId,
            @PathVariable UUID routeLogId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryRouteLogService.getRouteLog(deliveryId, routeLogId, userId, UserRole.from(role)),
                "배송 경로 기록을 조회했습니다."));
    }

    @Operation(summary = "배송 경로 기록 상태 변경",
            description = "IN_TRANSIT(READY→) 또는 ARRIVED(IN_TRANSIT→, actualDistanceKm 필수)만 가능. "
                    + "배송 상태도 IN_TRANSIT / ARRIVED_AT_HUB로 같이 바뀐다. "
                    + "MASTER는 전체, DELIVERY는 본인이 경로 기록 담당자인 경우만. 그 외 전이는 409")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND},
            value = {DELIVERY_ACCESS_DENIED, INVALID_STATUS_TRANSITION, DELIVERY_CONFLICT})
    @PatchMapping("/{routeLogId}/status")
    public ResponseEntity<ApiResponse<DeliveryRouteLogResponse>> changeRouteStatus(
            @PathVariable UUID deliveryId,
            @PathVariable UUID routeLogId,
            @Valid @RequestBody RouteLogStatusUpdateRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryRouteLogService.changeRouteStatus(deliveryId, routeLogId, request, userId, UserRole.from(role)),
                "배송 경로 기록 상태를 변경했습니다."));
    }

    @Operation(summary = "배송 경로 기록 담당자 지정 (MASTER)",
            description = "허브 간 이동 담당자를 지정하거나 다른 사람으로 바꾼다. MASTER가 아니면 403, "
                    + "배송이나 경로 기록이 없거나 그 배송의 경로 기록이 아니면 404, 허브를 출발(IN_TRANSIT)했거나 끝난(ARRIVED·CANCELLED) 경로 기록이면 409. "
                    + "지정할 회원이 없거나 탈퇴했으면 404, 비활성이거나 DELIVERY 역할이 아니면 422. user-service 장애 시 502")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION, DEPENDENCY_FAILURE},
            value = {INVALID_STATUS_TRANSITION, DELIVERY_CONFLICT})
    @PatchMapping("/{routeLogId}/manager")
    public ResponseEntity<ApiResponse<DeliveryRouteLogResponse>> assignRouteManager(
            @PathVariable UUID deliveryId,
            @PathVariable UUID routeLogId,
            @Valid @RequestBody RouteLogManagerAssignRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryRouteLogService.assignRouteManager(deliveryId, routeLogId, request.deliveryManagerId(),
                        UserRole.from(role)),
                "배송 경로 기록 담당자를 지정했습니다."));
    }
}
