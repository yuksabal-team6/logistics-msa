package com.yuksabal.company.product.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.auth.UserRole;
import com.yuksabal.company.global.swagger.ApiErrorCodes;
import com.yuksabal.company.product.dto.request.ProductCreateRequest;
import com.yuksabal.company.product.dto.response.ProductResponse;
import com.yuksabal.company.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

// 생성만 업체 하위 경로(/companies/{companyId}/products), 이후는 상품 ID 경로(/products/{productId})
@Tag(name = "Product", description = "상품 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ProductController {

    private final ProductService productService;

    // 생성: 201 + ApiResponse.success(data, message). MASTER 또는 해당 업체 관리자
    @Operation(summary = "상품 생성")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {COMPANY_ACCESS_DENIED})
    @PostMapping("/companies/{companyId}/products")
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @PathVariable UUID companyId,
            @Valid @RequestBody ProductCreateRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                productService.createProduct(companyId, request, userId, UserRole.from(role)),
                "상품이 생성되었습니다."));
    }

    // 단건 조회: 200 + ApiResponse.success(data, message). 로그인한 모든 역할 가능이라 사용자 헤더를 받지 않는다
    @Operation(summary = "상품 단건 조회")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable UUID productId) {

        return ResponseEntity.ok(ApiResponse.success(
                productService.getProduct(productId),
                "상품을 조회했습니다."));
    }
}
