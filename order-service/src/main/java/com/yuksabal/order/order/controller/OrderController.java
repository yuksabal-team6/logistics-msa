package com.yuksabal.order.order.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.order.global.swagger.ApiErrorCodes;
import com.yuksabal.order.order.dto.request.OrderCreateRequest;
import com.yuksabal.order.order.dto.request.OrderSearchRequest;
import com.yuksabal.order.order.dto.response.OrderCreateResponse;
import com.yuksabal.order.order.dto.response.OrderResponse;
import com.yuksabal.order.order.service.OrderCancelService;
import com.yuksabal.order.order.service.OrderCreateService;
import com.yuksabal.order.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.*;
import static com.yuksabal.order.global.exception.OrderErrorCode.IDEMPOTENCY_KEY_CONFLICT;
import static com.yuksabal.order.global.exception.OrderErrorCode.IDEMPOTENCY_KEY_MISMATCH;
import static com.yuksabal.order.global.exception.OrderErrorCode.INVALID_STATUS_TRANSITION;
import static com.yuksabal.order.global.exception.OrderErrorCode.ORDER_ALREADY_FAILED;
import static com.yuksabal.order.global.exception.OrderErrorCode.ORDER_IN_PROGRESS;
import static com.yuksabal.order.global.exception.OrderErrorCode.ORDER_NOT_DELETABLE;

// 주문 API. 상태는 생성,취소,배송 완료 흐름으로만 변경, 상태 변경 API 없음
@Tag(name = "Order", description = "주문 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderCreateService orderCreateService;
    private final OrderCancelService orderCancelService;

    // 게이트웨이가 JWT 검증 후 넣어주는 헤더. 서비스 포트(8084)로 직접 호출할 때만 직접 입력
    private static final String USER_ID_DESCRIPTION = "로그인한 사용자 ID입니다. 게이트웨이를 거치면 자동으로 들어갑니다.";
    private static final String USER_ROLE_DESCRIPTION = "로그인한 사용자 역할입니다. (MASTER, USER, COMPANY, DELIVERY) 게이트웨이를 거치면 자동으로 들어갑니다.";

    // 생성: 201 + ApiResponse.success(data, message). 로그인 사용자 모두 가능
    // Idempotency-Key(UUID) 필수: 누락, 형식 오류는 400 VALIDATION_ERROR (결정 #18)
    @Operation(summary = "주문 생성",
            description = """
                    **로그인한 사용자 모두** 주문할 수 있습니다.
                    
                    같은 Idempotency-Key로 다시 요청하면 새로 만들지 않고 **기존 주문 결과를 응답**합니다.

                    재고가 부족하면 hub의 **409 INSUFFICIENT_STOCK**을 그대로 응답합니다.
                    """)
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, DEPENDENCY_FAILURE},
            value = {ORDER_IN_PROGRESS, ORDER_ALREADY_FAILED, IDEMPOTENCY_KEY_CONFLICT, IDEMPOTENCY_KEY_MISMATCH})
    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreateResponse>> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            @Parameter(description = "중복 주문을 막는 키입니다. 주문마다 **새 UUID**를 넣어 주세요. (**필수**, 같은 키로 다시 요청하면 기존 결과를 응답합니다)")
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                orderCreateService.createOrder(request, idempotencyKey, userId, UserRole.from(role)),
                "주문이 생성되었습니다."));
    }

    // 목록 조회: MASTER는 전체, 그 외는 본인 주문만. 검색 조건은 모두 선택
    @Operation(summary = "주문 목록 조회 및 검색",
            description = """
                    **MASTER**는 전체 주문을, 그 외 로그인 사용자는 **본인 주문만** 조회합니다.
                    
                    정렬은 createdAt, totalAmount만 가능합니다. (기본값 createdAt,desc)
                    
                    기간은 yyyy-MM-dd 형식이며, 시작일과 종료일을 **모두 포함**합니다. (한국 시간)
                    """)
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN})
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> getOrders(
            @Valid @ParameterObject OrderSearchRequest request,
            @ParameterObject Pageable pageable,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(ApiResponse.success(
                orderService.getOrders(request, pageable, userId, UserRole.from(role)),
                "목록을 조회했습니다."));
    }

    // 상세 조회: MASTER 또는 주문자 본인
    @Operation(summary = "주문 상세 조회", description = "**MASTER** 또는 **주문자 본인**만 조회할 수 있습니다.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND})
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable UUID orderId,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(ApiResponse.success(
                orderService.getOrder(orderId, userId, UserRole.from(role)),
                "주문을 조회했습니다."));
    }

    // 취소: 200 + 취소된 주문. 허브 출발 전(READY)만 가능 (결정 #19)
    @Operation(summary = "주문 취소",
            description = """
                    **주문자 본인, 요청 업체 관리자, MASTER**가 취소할 수 있습니다.
                    
                    **허브 출발 전에만** 가능하며, 취소하면 재고를 복원합니다.
                    
                    확정(CONFIRMED)된 주문만 취소할 수 있습니다. 이미 출발한 배송이면 delivery의 **409 INVALID_STATUS_TRANSITION**을, 다른 요청이 배송을 수정 중이면 **409 DELIVERY_CONFLICT**를 그대로 응답합니다.
                    
                    배송 취소 결과를 확인하지 못하면 **502**를 응답하며, 취소 상태는 유지되고 운영자가 확인합니다.
                    """)
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, DEPENDENCY_FAILURE}, value = {INVALID_STATUS_TRANSITION})
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable UUID orderId,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(ApiResponse.success(
                orderCancelService.cancelOrder(orderId, userId, UserRole.from(role)),
                "주문이 취소되었습니다."));
    }

    // 삭제: 200 + data 없음. MASTER만, 완료 주문 또는 보상이 끝난(필요 없는) 실패/취소 주문만 가능
    @Operation(summary = "주문 삭제",
            description = """
                    **MASTER**만 삭제할 수 있습니다.
                    
                    **COMPLETED** 주문, 또는 compensationStatus가 NOT_REQUIRED/COMPLETED인 **FAILED/CANCELLED** 주문만 삭제할 수 있습니다.
                    """)
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {ORDER_NOT_DELETABLE})
    @DeleteMapping("/{orderId}")
    public ResponseEntity<ApiResponse<Void>> deleteOrder(
            @PathVariable UUID orderId,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {

        orderService.deleteOrder(orderId, userId, UserRole.from(role));
        return ResponseEntity.ok(ApiResponse.success(null, "주문이 삭제되었습니다."));
    }
}
