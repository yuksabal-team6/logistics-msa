package com.yuksabal.delivery.delivery.controller;

import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.delivery.delivery.dto.request.DeliveryCancelRequest;
import com.yuksabal.delivery.delivery.dto.request.DeliveryCreateRequest;
import com.yuksabal.delivery.delivery.dto.response.DeliveryCreateResponse;
import com.yuksabal.delivery.delivery.dto.response.DeliveryInProgressResponse;
import com.yuksabal.delivery.delivery.service.DeliveryService;
import com.yuksabal.delivery.global.exception.DeliveryErrorCode;
import com.yuksabal.delivery.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Delivery Internal", description = "배송 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/deliveries")
public class DeliveryInternalController {

    private final DeliveryService deliveryService;


    @Operation(summary = "배송 생성", description = "같은 orderId로 다시 요청하면 기존 배송을 반환한다. "
            + "ordererId는 선택. 넣으면 주문자 본인(역할 무관)이 배송을 조회할 수 있다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR})
    @PostMapping
    public ResponseEntity<ApiResponse<DeliveryCreateResponse>> createDelivery(
            @Valid @RequestBody DeliveryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(deliveryService.createDelivery(request), "배송이 생성되었습니다."));
    }


    @Operation(summary = "배송·경로 기록 취소",
            description = "허브 출발 전(READY)에만 취소한다. 배송이 없거나 이미 취소됐으면 아무것도 하지 않고 성공(200)으로 반환한다. "
                    + "이미 완료된 배송은 409 DELIVERY_ALREADY_COMPLETED, 그 외 출발 후 상태는 409 INVALID_STATUS_TRANSITION.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR},
            value = {DeliveryErrorCode.DELIVERY_ALREADY_COMPLETED, DeliveryErrorCode.INVALID_STATUS_TRANSITION})
    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelDelivery(@Valid @RequestBody DeliveryCancelRequest request) {
        deliveryService.cancelDelivery(request.orderId());
        return ResponseEntity.ok(ApiResponse.success(null, "배송이 취소되었습니다."));
    }

    // 회원의 진행 중 배송 존재 여부. user가 회원 탈퇴(소프트 삭제) 전 호출, 담당 건이 없으면 false
    @Operation(summary = "회원 진행 중 배송 존재 여부",
            description = """
                    **user-service**가 회원 탈퇴 전에 호출합니다.

                    배송(허브→업체) 담당자로 진행 중(READY, IN_TRANSIT, ARRIVED_AT_HUB, DELIVERING)인 배송이 있거나,
                    경로 기록(허브→허브) 담당자로 진행 중(READY, IN_TRANSIT)인 경로 기록이 있으면 **true**입니다.
                    """)
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR})
    @GetMapping("/in-progress")
    public ResponseEntity<ApiResponse<DeliveryInProgressResponse>> hasInProgressDelivery(
            @RequestParam UUID deliveryManagerId) {
        return ResponseEntity.ok(ApiResponse.success(
                deliveryService.hasInProgressDelivery(deliveryManagerId),
                "진행 중 배송 여부를 조회했습니다."));
    }

}
