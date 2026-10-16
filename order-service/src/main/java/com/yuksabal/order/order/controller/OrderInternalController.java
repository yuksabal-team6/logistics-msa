package com.yuksabal.order.order.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.global.swagger.ApiErrorCodes;
import com.yuksabal.order.order.dto.response.OrderCompleteResponse;
import com.yuksabal.order.order.dto.response.OrderInProgressResponse;
import com.yuksabal.order.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.order.global.exception.OrderErrorCode.INVALID_STATUS_TRANSITION;

// 다른 서비스가 Feign으로 호출하는 내부 API. 게이트웨이에서 외부 차단, 사용자 권한 검사 없음
@Tag(name = "Order Internal", description = "주문 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/orders")
public class OrderInternalController {

    private final OrderService orderService;

    // 배송 완료 → 주문 COMPLETED. delivery가 배송 완료 커밋 후 호출
    @Operation(summary = "주문 완료 처리 (#10)",
            description = """
                    **delivery-service**가 배송 완료 후 호출합니다.
                    
                    **CONFIRMED** 주문만 COMPLETED로 바꾸며, 이미 COMPLETED면 변경 없이 성공합니다.
                    """)
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND}, value = {INVALID_STATUS_TRANSITION})
    @PostMapping("/{orderId}/complete")
    public ResponseEntity<ApiResponse<OrderCompleteResponse>> completeOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(ApiResponse.success(
                orderService.completeOrder(orderId),
                "주문이 완료 처리되었습니다."));
    }

    // 회원의 진행 중 주문 존재 여부. user가 회원 탈퇴(소프트 삭제) 전 호출, 주문이 없으면 false
    @Operation(summary = "회원 진행 중 주문 존재 여부",
            description = """
                    **user-service**가 회원 탈퇴 전에 호출합니다.

                    PENDING, CONFIRMED 주문 또는 보상이 끝나지 않은 FAILED/CANCELLED 주문이 있으면 **true**입니다.
                    """)
    @ApiErrorCodes(common = {VALIDATION_ERROR})
    @GetMapping("/in-progress")
    public ResponseEntity<ApiResponse<OrderInProgressResponse>> hasInProgressOrder(@RequestParam UUID ordererId) {
        return ResponseEntity.ok(ApiResponse.success(
                orderService.hasInProgressOrder(ordererId),
                "진행 중 주문 여부를 조회했습니다."));
    }
}
