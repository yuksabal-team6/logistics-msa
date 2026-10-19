package com.yuksabal.company.product.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.company.global.swagger.ApiErrorCodes;
import com.yuksabal.company.product.dto.request.ProductCreateRequest;
import com.yuksabal.company.product.dto.request.ProductSearchRequest;
import com.yuksabal.company.product.dto.request.ProductStatusRequest;
import com.yuksabal.company.product.dto.request.ProductUpdateRequest;
import com.yuksabal.company.product.dto.response.ProductResponse;
import com.yuksabal.company.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
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
    @Operation(summary = "상품 생성", description = "비활성 업체에는 등록할 수 없다 (422)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION}, value = {COMPANY_ACCESS_DENIED})
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

    // 목록 조회: 200 + ApiResponse.success(PageResponse, message). 검색 조건은 모두 선택
    @Operation(summary = "상품 목록 조회 및 검색", description = "정렬 가능: createdAt, productName, unitPrice. active로 활성·비활성 상품만 조회 가능")
    @ApiErrorCodes(common = {VALIDATION_ERROR})
    @GetMapping("/products")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> getProducts(
            @Valid @ParameterObject ProductSearchRequest request,
            @ParameterObject Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(
                productService.getProducts(request, pageable),
                "목록을 조회했습니다."));
    }

    // 수정: 200 + ApiResponse.success(data, message). 보낸 필드만 수정, MASTER 또는 소속 업체 관리자
    @Operation(summary = "상품 수정")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {COMPANY_ACCESS_DENIED})
    @PatchMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductUpdateRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(ApiResponse.success(
                productService.updateProduct(productId, request, userId, UserRole.from(role)),
                "상품이 수정되었습니다."));
    }

    // 상태 변경: 200 + ApiResponse.success(data, message). MASTER 또는 소속 업체 관리자, 같은 상태면 변경 없이 200
    @Operation(summary = "상품 활성화·비활성화",
            description = "비활성 상품의 새 주문·입고는 order·hub가 내부 API의 active를 확인해 막고, 진행 중인 주문·배송은 그대로 진행된다. "
                    + "비활성 업체의 상품은 활성화할 수 없다(422). 같은 상태면 변경 없이 200")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION}, value = {COMPANY_ACCESS_DENIED})
    @PatchMapping("/products/{productId}/status")
    public ResponseEntity<ApiResponse<ProductResponse>> changeProductStatus(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductStatusRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(ApiResponse.success(
                productService.changeProductStatus(productId, request, userId, UserRole.from(role)),
                "상품 상태가 변경되었습니다."));
    }

    // 삭제: 200 + ApiResponse.success(null, message). MASTER 또는 소속 업체 관리자, 응답 data 없음
    @Operation(summary = "상품 삭제")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {COMPANY_ACCESS_DENIED})
    @DeleteMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(
            @PathVariable UUID productId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        productService.deleteProduct(productId, userId, UserRole.from(role));
        return ResponseEntity.ok(ApiResponse.success(null, "상품이 삭제되었습니다."));
    }
}
