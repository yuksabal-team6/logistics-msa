package com.yuksabal.company.company.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.company.dto.request.CompanyCreateRequest;
import com.yuksabal.company.company.dto.response.CompanyResponse;
import com.yuksabal.company.company.service.CompanyService;
import com.yuksabal.company.global.auth.UserRole;
import com.yuksabal.company.global.swagger.ApiErrorCodes;
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

import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

@Tag(name = "Company", description = "업체 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyService companyService;

    // 생성: 201 + ApiResponse.success(data, message). 역할 헤더는 여기서 UserRole로 변환해 넘긴다
    @Operation(summary = "업체 생성")
    @ApiErrorCodes(common = {VALIDATION_ERROR, FORBIDDEN, DUPLICATE_RESOURCE}, value = {COMPANY_ACCESS_DENIED})
    @PostMapping
    public ResponseEntity<ApiResponse<CompanyResponse>> createCompany(
            @Valid @RequestBody CompanyCreateRequest request,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        CompanyResponse response = companyService.createCompany(request, userId, UserRole.from(role));

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(response, "업체가 생성되었습니다."));
    }

    // 단건 조회: 200 + ApiResponse.success(data, message). 로그인한 모든 역할 가능이라 사용자 헤더를 받지 않는다
    @Operation(summary = "업체 단건 조회")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/{companyId}")
    public ResponseEntity<ApiResponse<CompanyResponse>> getCompany(@PathVariable UUID companyId) {
        CompanyResponse response = companyService.getCompany(companyId);

        return ResponseEntity.ok(
                ApiResponse.success(response, "업체를 조회했습니다."));
    }
}
