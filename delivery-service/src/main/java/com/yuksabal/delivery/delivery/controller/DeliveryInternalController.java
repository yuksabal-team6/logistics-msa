package com.yuksabal.delivery.delivery.controller;

import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.delivery.delivery.dto.DeliveryCreateRequest;
import com.yuksabal.delivery.delivery.dto.DeliveryCreateResponse;
import com.yuksabal.delivery.delivery.service.DeliveryService;
import com.yuksabal.delivery.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery Internal", description = "배송 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/deliveries")
public class DeliveryInternalController {

    private final DeliveryService deliveryService;


    @Operation(summary = "배송 생성", description = "같은 orderId로 다시 요청하면 기존 배송을 반환한다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR})
    @PostMapping
    public ResponseEntity<ApiResponse<DeliveryCreateResponse>> createDelivery(
            @Valid @RequestBody DeliveryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(deliveryService.createDelivery(request), "배송이 생성되었습니다."));
    }

}
