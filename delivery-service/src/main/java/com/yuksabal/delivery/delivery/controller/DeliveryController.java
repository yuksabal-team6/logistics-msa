package com.yuksabal.delivery.delivery.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.delivery.delivery.dto.request.DeliveryManagerAssignRequest;
import com.yuksabal.delivery.delivery.dto.request.DeliverySearchRequest;
import com.yuksabal.delivery.delivery.dto.request.DeliveryStatusUpdateRequest;
import com.yuksabal.delivery.delivery.dto.response.DeliveryResponse;
import com.yuksabal.delivery.delivery.service.DeliveryService;
import com.yuksabal.delivery.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_ACCESS_DENIED;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_CONFLICT;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;

@Tag(name = "Delivery", description = "배송 외부 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    // ?deliveryStatus=&departureHubId=&destinationHubId=&deliveryManagerId=&page=0&size=20&sort=createdAt,desc (조건은 모두 선택)
    @Operation(summary = "배송 목록 조회 및 검색",
            description = "MASTER는 전체, 그 외에는 본인이 주문한 배송만 조회한다. "
                    + "DELIVERY는 본인 담당(배송 담당자 또는 경로 기록 담당자) 배송도 조회한다. "
                    + "주문자 정보가 없는 배송은 MASTER와 담당 DELIVERY만 조회할 수 있다. 정렬 가능: createdAt")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN})
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DeliveryResponse>>> getDeliveries(
            @ParameterObject DeliverySearchRequest request,
            @ParameterObject Pageable pageable,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryService.getDeliveries(request, pageable, userId, UserRole.from(role)),
                "목록을 조회했습니다."));
    }

    @Operation(summary = "배송 상세 조회",
            description = "배송이 없으면 404. MASTER, 주문자 본인(역할 무관), "
                    + "담당 DELIVERY(배송 담당자 또는 경로 기록 담당자)가 아니면 403.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {DELIVERY_ACCESS_DENIED})
    @GetMapping("/{deliveryId}")
    public ResponseEntity<ApiResponse<DeliveryResponse>> getDelivery(
            @PathVariable UUID deliveryId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryService.getDelivery(deliveryId, userId, UserRole.from(role)),
                "배송을 조회했습니다."));
    }

    @Operation(summary = "배송 상태 변경",
            description = "DELIVERING(같은 허브: READY→, 다른 허브: ARRIVED_AT_HUB→) 또는 COMPLETED(DELIVERING→)만 가능. "
                    + "MASTER는 전체, DELIVERY는 본인이 배송 담당자인 배송만. 그 외 전이는 409")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND},
            value = {DELIVERY_ACCESS_DENIED, INVALID_STATUS_TRANSITION, DELIVERY_CONFLICT})
    @PatchMapping("/{deliveryId}/status")
    public ResponseEntity<ApiResponse<DeliveryResponse>> changeDeliveryStatus(
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryStatusUpdateRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryService.changeDeliveryStatus(deliveryId, request.deliveryStatus(), userId, UserRole.from(role)),
                "배송 상태를 변경했습니다."));
    }

    @Operation(summary = "배송 담당자 지정 (MASTER)",
            description = "배송(허브→업체) 담당자를 지정하거나 다른 사람으로 바꾼다. MASTER가 아니면 403, 배송이 없으면 404, "
                    + "업체로 출발(DELIVERING)했거나 끝난(COMPLETED·CANCELLED) 배송이면 409. 지정할 회원이 없거나 탈퇴했으면 404, "
                    + "비활성이거나 DELIVERY 역할이 아니면 422. user-service 장애 시 502")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION, DEPENDENCY_FAILURE},
            value = {INVALID_STATUS_TRANSITION, DELIVERY_CONFLICT})
    @PatchMapping("/{deliveryId}/manager")
    public ResponseEntity<ApiResponse<DeliveryResponse>> assignDeliveryManager(
            @PathVariable UUID deliveryId,
            @Valid @RequestBody DeliveryManagerAssignRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryService.assignDeliveryManager(deliveryId, request.deliveryManagerId(), UserRole.from(role)),
                "배송 담당자를 지정했습니다."));
    }

    // 삭제: 200 + ApiResponse.success(null, message). MASTER만 가능, 응답 data 없음
    @Operation(summary = "배송 삭제 (MASTER)",
            description = "끝난(COMPLETED·CANCELLED) 배송만 삭제할 수 있다. 배송과 경로 기록을 함께 Soft Delete한다. "
                    + "MASTER가 아니면 403, 배송이 없으면 404, 진행 중인 배송이면 409")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND},
            value = {INVALID_STATUS_TRANSITION, DELIVERY_CONFLICT})
    @DeleteMapping("/{deliveryId}")
    public ResponseEntity<ApiResponse<Void>> deleteDelivery(
            @PathVariable UUID deliveryId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        deliveryService.deleteDelivery(deliveryId, userId, UserRole.from(role));
        return ResponseEntity.ok(ApiResponse.success(null, "배송을 삭제했습니다."));
    }
}
