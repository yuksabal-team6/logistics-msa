package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.inventory.dto.request.StockRequest;
import com.yuksabal.hub.inventory.dto.response.StockDecreaseResponse;
import com.yuksabal.hub.inventory.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.hub.global.exception.HubErrorCode.INSUFFICIENT_STOCK;

// 게이트웨이에 라우팅하지 않는 Feign 전용 API (order-service HubClient가 호출)
@Tag(name = "Stock Internal", description = "주문 재고 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/stocks")
public class StockInternalController {

    private final StockService stockService;

    // X-User-Id는 order가 사용자 요청을 처리하다 호출하면 Feign이 전달해 준다.
    // 스케줄러·비동기(보상 재시도 등)에서 호출하면 없어서 updatedBy는 null
    @Operation(summary = "주문 재고 차감",
            description = "허브의 상품 재고를 주문 수량만큼 차감한다. 같은 orderId로 이미 차감했거나 복원된 주문이면 변경 없이 200 (현재 수량). "
                    + "재고가 없으면 404, 남은 수량이 부족하면 409 INSUFFICIENT_STOCK (details에 현재 수량)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND}, value = {INSUFFICIENT_STOCK})
    @PostMapping("/decrease")
    public ResponseEntity<ApiResponse<StockDecreaseResponse>> decreaseStock(
            @Valid @RequestBody StockRequest request,
            @Parameter(description = "주문 요청자 ID (Feign이 전달)", example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(stockService.decreaseStock(request, userId)));
    }
}