package com.yuksabal.company.product.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.swagger.ApiErrorCodes;
import com.yuksabal.company.product.dto.response.ProductInternalResponse;
import com.yuksabal.company.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 다른 서비스가 Feign으로 호출하는 내부 API. 게이트웨이에 라우팅되지 않으며, 사용자 권한을 검사하지 않는다(명세 0.3)
@Tag(name = "Product Internal", description = "상품 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/products")
public class ProductInternalController {

    private final ProductService productService;

    // #1 상품 조회: 200 + ApiResponse.success(data). 없거나 삭제된 상품은 404 → order에서 주문 실패로 처리
    @Operation(summary = "상품 조회 (#1)", description = "order가 주문 생성 시 호출. 없거나 삭제된 상품은 404")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductInternalResponse>> getProductForInternal(@PathVariable UUID productId) {

        return ResponseEntity.ok(ApiResponse.success(
                productService.getProductForInternal(productId)));
    }
}
