package com.yuksabal.order.order.controller;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.global.swagger.ApiErrorCodes;
import com.yuksabal.order.order.dto.response.OrderCompleteResponse;
import com.yuksabal.order.order.dto.response.OrderInProgressResponse;
import com.yuksabal.order.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.order.global.exception.OrderErrorCode.INVALID_STATUS_TRANSITION;

// 다른 서비스가 Feign으로 호출하는 내부 API. 게이트웨이에서 외부 차단, 사용자 권한 검사 없음
@Slf4j
@Tag(name = "Order Internal", description = "주문 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/orders")
public class OrderInternalController {

    private final OrderService orderService;

    // 배송 완료 → 주문 COMPLETED. delivery가 배송 완료 커밋 후 호출
    @Operation(summary = "주문 완료 처리",
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

    // 진행 중 주문 존재 여부. 주문이 없으면 false
    // user: 회원 탈퇴 전 ordererId / company: 상품 삭제 전 productId, 업체 삭제 전 companyId
    @Operation(summary = "진행 중 주문 존재 여부",
            description = """
                    **ordererId, productId, companyId 중 하나만** 보내야 합니다. (없거나 여러 개면 400)

                    - ordererId: **user-service**가 회원 탈퇴 전에 호출합니다.
                    - productId: **company-service**가 상품 삭제 전에 호출합니다.
                    - companyId: **company-service**가 업체 삭제 전에 호출합니다. 공급 업체, 요청 업체로 들어간 주문을 모두 확인합니다.

                    PENDING, CONFIRMED 주문 또는 보상이 끝나지 않은 FAILED/CANCELLED 주문이 있으면 **true**입니다.
                    """)
    @ApiErrorCodes(common = {VALIDATION_ERROR})
    @GetMapping("/in-progress")
    public ResponseEntity<ApiResponse<OrderInProgressResponse>> hasInProgressOrder(
            @RequestParam(required = false) UUID ordererId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID companyId) {

        // 3개 중 1개만 받는다.
        if (Stream.of(ordererId, productId, companyId).filter(Objects::nonNull).count() != 1) {
            log.warn("[진행 중 주문] 파라미터는 하나만 ordererId={}, productId={}, companyId={}", ordererId, productId, companyId);
            throw new BusinessException(VALIDATION_ERROR, "ordererId, productId, companyId 중 하나만 보내 주세요.");
        }
        OrderInProgressResponse response;
        if (ordererId != null) {
            response = orderService.hasInProgressOrder(ordererId);
        } else if (productId != null) {
            response = orderService.hasInProgressOrderByProduct(productId);
        } else {
            response = orderService.hasInProgressOrderByCompany(companyId);
        }
        return ResponseEntity.ok(ApiResponse.success(response, "진행 중 주문 여부를 조회했습니다."));
    }
}
