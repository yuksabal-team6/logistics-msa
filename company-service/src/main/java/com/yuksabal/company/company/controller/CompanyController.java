package com.yuksabal.company.company.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.company.company.dto.request.CompanyCreateRequest;
import com.yuksabal.company.company.dto.request.CompanySearchRequest;
import com.yuksabal.company.company.dto.request.CompanyStatusRequest;
import com.yuksabal.company.company.dto.request.CompanyUpdateRequest;
import com.yuksabal.company.company.dto.response.CompanyResponse;
import com.yuksabal.company.company.service.CompanyService;
import com.yuksabal.company.global.swagger.ApiErrorCodes;
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
import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;
import static com.yuksabal.company.global.exception.CompanyErrorCode.HAS_ACTIVE_WORK;

@Tag(name = "Company", description = "업체 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyService companyService;

    // 생성: 201 + ApiResponse.success(data, message). 역할 헤더는 여기서 UserRole로 변환해 넘긴다
    @Operation(summary = "업체 생성")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, DUPLICATE_RESOURCE, BUSINESS_RULE_VIOLATION, DEPENDENCY_FAILURE}, value = {COMPANY_ACCESS_DENIED})
    @PostMapping
    public ResponseEntity<ApiResponse<CompanyResponse>> createCompany(
            @Valid @RequestBody CompanyCreateRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                companyService.createCompany(request, userId, UserRole.from(role)),
                "업체가 생성되었습니다."));
    }

    // 단건 조회: 200 + ApiResponse.success(data, message). 로그인한 모든 역할 가능이라 사용자 헤더를 받지 않는다
    @Operation(summary = "업체 단건 조회")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/{companyId}")
    public ResponseEntity<ApiResponse<CompanyResponse>> getCompany(@PathVariable UUID companyId) {

        return ResponseEntity.ok(ApiResponse.success(
                companyService.getCompany(companyId),
                "업체를 조회했습니다."));
    }

    // 목록 조회: 200 + ApiResponse.success(PageResponse, message). 검색 조건은 모두 선택
    @Operation(summary = "업체 목록 조회 및 검색", description = "정렬 가능: createdAt, companyName. active로 활성·비활성 업체만 조회 가능")
    @ApiErrorCodes(common = {VALIDATION_ERROR})
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CompanyResponse>>> getCompanies(
            @Valid @ParameterObject CompanySearchRequest request,
            @ParameterObject Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(
                companyService.getCompanies(request, pageable),
                "목록을 조회했습니다."));
    }

    // 수정: 200 + ApiResponse.success(data, message). 보낸 필드만 수정, 허브·관리자 변경은 MASTER만
    // 관리 허브 변경은 업체 상품의 재고·진행 중 주문이 남아 있으면 409 (#161)
    @Operation(summary = "업체 수정",
            description = "보낸 필드만 수정한다. 관리 허브·관리자 변경은 MASTER만 가능. "
                    + "관리 허브는 업체 상품의 허브 재고나 공급·요청 업체로 걸린 진행 중 주문이 남아 있으면 바꿀 수 없다(409), 확인하지 못하면 502")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, DUPLICATE_RESOURCE, BUSINESS_RULE_VIOLATION, DEPENDENCY_FAILURE},
            value = {COMPANY_ACCESS_DENIED, HAS_ACTIVE_WORK})
    @PatchMapping("/{companyId}")
    public ResponseEntity<ApiResponse<CompanyResponse>> updateCompany(
            @PathVariable UUID companyId,
            @Valid @RequestBody CompanyUpdateRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(ApiResponse.success(
                companyService.updateCompany(companyId, request, userId, UserRole.from(role)),
                "업체가 수정되었습니다."));
    }

    // 상태 변경: 200 + ApiResponse.success(data, message). MASTER만 가능, 같은 상태면 변경 없이 200
    @Operation(summary = "업체 활성화·비활성화 (MASTER)",
            description = "비활성화하면 소속 상품도 함께 비활성화되고 상품 등록이 막힌다. "
                    + "새 주문(공급·요청)·입고는 order·hub가 내부 API의 active를 확인해 막고, 진행 중인 주문·배송은 그대로 진행된다. "
                    + "다시 활성화해도 상품은 비활성으로 남는다. 같은 상태면 변경 없이 200")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND})
    @PatchMapping("/{companyId}/status")
    public ResponseEntity<ApiResponse<CompanyResponse>> changeCompanyStatus(
            @PathVariable UUID companyId,
            @Valid @RequestBody CompanyStatusRequest request,
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(ApiResponse.success(
                companyService.changeCompanyStatus(companyId, request, UserRole.from(role)),
                "업체 상태가 변경되었습니다."));
    }

    // 삭제: 200 + ApiResponse.success(null, message). MASTER만 가능, 응답 data 없음
    @Operation(summary = "업체 삭제",
            description = "비활성 업체만 삭제 가능(422). 이 업체 상품의 허브 재고나 이 업체가 공급·요청한 진행 중 주문이 있으면 409, "
                    + "확인하지 못하면 502. 소속 상품도 함께 삭제")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION, DEPENDENCY_FAILURE},
            value = {HAS_ACTIVE_WORK})
    @DeleteMapping("/{companyId}")
    public ResponseEntity<ApiResponse<Void>> deleteCompany(
            @PathVariable UUID companyId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        companyService.deleteCompany(companyId, userId, UserRole.from(role));
        return ResponseEntity.ok(ApiResponse.success(null, "업체가 삭제되었습니다."));
    }
}
