package com.yuksabal.hub.inventory.presentation;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.inventory.application.dto.InventoryStockCondition;
import com.yuksabal.hub.inventory.presentation.dto.request.InventoryStockRequest;
import com.yuksabal.hub.inventory.presentation.dto.response.InventoryStockResponse;
import com.yuksabal.hub.inventory.application.InventoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 게이트웨이에 라우팅하지 않는 Feign 전용 API (company-service가 호출)
@Tag(name = "Inventory Internal", description = "재고 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/inventories")
public class InventoryInternalController {

    private final InventoryQueryService inventoryQueryService;

    // ?productId={상품 ID} 또는 ?companyId={업체 ID}
    @Operation(summary = "상품·업체 기준 재고 보유 여부",
            description = "상품 또는 업체의 재고 중 남은 수량이 0보다 큰 것이 있는지 확인한다 (상품·업체 삭제 전 확인용). "
                    + "수량 0인 재고나 재고가 없으면 hasStock false. productId, companyId 중 하나만 보내야 하며 아니면 400")
    @ApiErrorCodes(common = {VALIDATION_ERROR})
    @GetMapping("/stock")
    public ResponseEntity<ApiResponse<InventoryStockResponse>> hasStock(
            @Valid @ParameterObject InventoryStockRequest request) {
        InventoryStockCondition condition = new InventoryStockCondition(request.productId(), request.companyId());
        return ResponseEntity.ok(ApiResponse.success(new InventoryStockResponse(inventoryQueryService.hasStock(condition))));
    }
}
