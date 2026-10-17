package com.yuksabal.user.user.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.global.swagger.ApiErrorCodes;
import com.yuksabal.user.user.dto.response.UserInternalResponse;
import com.yuksabal.user.user.service.UserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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
@Tag(name = "User Internal", description = "회원 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/users")
public class UserInternalController {

    private final UserQueryService userQueryService;

    // 회원 조회: 200 + ApiResponse.success(data). 없거나 탈퇴한 회원은 404 → 호출하는 쪽에서 배정·지정 실패 등으로 처리
    // 비활성 회원도 200(active=false), 역할·소속이 맞는지는 호출 측이 판단한다
    @Operation(summary = "회원 조회 (내부)",
            description = "delivery·company·order가 호출. 활성 여부·역할·소속(hubId, companyId)을 반환. 없거나 탈퇴한 회원은 404")
    @SecurityRequirements
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserInternalResponse>> getUser(
            @Parameter(description = "회원 ID", example = "10000000-0000-4000-8000-000000000001")
            @PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(userQueryService.getInternalUser(userId)));
    }
}
