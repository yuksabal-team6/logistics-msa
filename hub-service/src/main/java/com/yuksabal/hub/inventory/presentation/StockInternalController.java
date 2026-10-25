package com.yuksabal.hub.inventory.presentation;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.inventory.application.dto.StockCommand;
import com.yuksabal.hub.inventory.presentation.dto.request.StockRequest;
import com.yuksabal.hub.inventory.presentation.dto.response.StockDecreaseResponse;
import com.yuksabal.hub.inventory.presentation.dto.response.StockRestoreResponse;
import com.yuksabal.hub.inventory.application.StockService;
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

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
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

    // X-User-Id는 order가 사용자 요청(주문 생성 보상, 주문 취소)을 처리하다 호출하면 Feign 인터셉터가 전달해 준다
    // 형식 검증(UUID가 아니면 400)과 문서용으로만 받는다. updatedBy는 JPA Auditing이 같은 헤더에서 읽어 채운다 (#273)
    // 헤더가 없으면(스케줄러·비동기 호출) Auditing은 기존 updatedBy를 그대로 둔다
    @Operation(summary = "주문 재고 차감",
            description = "허브의 상품 재고를 주문 수량만큼 차감한다. 같은 orderId로 이미 차감했거나 복원된 주문이면 변경 없이 200 (현재 수량). "
                    + "재고가 없으면 404, 남은 수량이 부족하면 409 INSUFFICIENT_STOCK (details에 현재 수량)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND}, value = {INSUFFICIENT_STOCK})
    @PostMapping("/decrease")
    public ResponseEntity<ApiResponse<StockDecreaseResponse>> decreaseStock(
            @Valid @RequestBody StockRequest request,
            @Parameter(description = "주문 요청자 ID (Feign이 전달)", example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        StockDecreaseResponse response = StockDecreaseResponse.from(stockService.decreaseStock(toCommand(request)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 배송 생성 실패 보상, 주문 취소 시 호출된다. 복원 수량은 요청 quantity가 아니라 실제로 차감한 수량이다
    @Operation(summary = "주문 재고 복원",
            description = "같은 orderId로 차감한 수량만큼 재고를 되돌린다. 이미 복원한 주문이면 변경 없이 200 (처음 복원한 수량). "
                    + "차감 기록이 없으면 재고는 그대로 두고 200 (restoredQuantity 0). 해당 허브에 상품 재고가 없으면 404, "
                    + "차감한 재고와 요청의 허브·상품이 다르면 422 BUSINESS_RULE_VIOLATION (details에 차감한 허브·상품)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION})
    @PostMapping("/restore")
    public ResponseEntity<ApiResponse<StockRestoreResponse>> restoreStock(
            @Valid @RequestBody StockRequest request,
            @Parameter(description = "주문 요청자 ID (Feign이 전달)", example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        StockRestoreResponse response = StockRestoreResponse.from(stockService.restoreStock(toCommand(request)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 차감·복원이 같은 요청 형식이라 변환을 한 곳에 둔다
    private static StockCommand toCommand(StockRequest request) {
        return new StockCommand(request.hubId(), request.productId(), request.quantity(), request.orderId());
    }
}