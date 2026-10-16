package com.yuksabal.company.company.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.company.dto.response.CompanyInternalResponse;
import com.yuksabal.company.company.service.CompanyService;
import com.yuksabal.company.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 다른 서비스가 Feign으로 호출하는 내부 API. 게이트웨이에 라우팅되지 않으며, 사용자 권한을 검사하지 않는다(명세 0.3)
@Tag(name = "Company Internal", description = "업체 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/companies")
public class CompanyInternalController {

    private final CompanyService companyService;

    // #2 업체 조회: 200 + ApiResponse.success(data). 없거나 삭제된 업체는 404 → 호출하는 쪽에서 주문 실패 등으로 처리
    @Operation(summary = "업체 조회 (#2)", description = "order·user·hub가 호출. 없거나 삭제된 업체는 404")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/{companyId}")
    public ResponseEntity<ApiResponse<CompanyInternalResponse>> getCompanyForInternal(@PathVariable UUID companyId) {

        return ResponseEntity.ok(ApiResponse.success(
                companyService.getCompanyForInternal(companyId)));
    }

    // #11 관리자 ID로 업체 조회: 200 + 목록 (빈 목록 가능)
    @Operation(summary = "관리자 ID로 업체 조회 (#11)", description = "user가 회원 삭제·관리자 소속·역할 변경 전에 호출. 없으면 빈 목록")
    @ApiErrorCodes(common = {VALIDATION_ERROR})
    @GetMapping
    public ResponseEntity<ApiResponse<List<CompanyInternalResponse>>> getCompaniesByOwnerForInternal(
            @RequestParam UUID ownerId) {

        return ResponseEntity.ok(ApiResponse.success(
                companyService.getCompaniesByOwnerForInternal(ownerId)));
    }
}
