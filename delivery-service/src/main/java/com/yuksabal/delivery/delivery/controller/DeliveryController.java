package com.yuksabal.delivery.delivery.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.delivery.delivery.dto.request.DeliverySearchRequest;
import com.yuksabal.delivery.delivery.dto.response.DeliveryResponse;
import com.yuksabal.delivery.delivery.service.DeliveryService;
import com.yuksabal.delivery.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

@Tag(name = "Delivery", description = "배송 외부 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    // ?deliveryStatus=&departureHubId=&destinationHubId=&deliveryManagerId=&page=0&size=20&sort=createdAt,desc (조건은 모두 선택)
    @Operation(summary = "배송 목록 조회 및 검색",
            description = "DELIVERY는 본인 담당 배송만 조회한다. 정렬 가능: createdAt")
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
            description = "배송이 없으면 404. DELIVERY가 본인 담당이 아닌 배송을 조회하면 403.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND})
    @GetMapping("/{deliveryId}")
    public ResponseEntity<ApiResponse<DeliveryResponse>> getDelivery(
            @PathVariable UUID deliveryId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryService.getDelivery(deliveryId, userId, UserRole.from(role)),
                "배송을 조회했습니다."));
    }
}
