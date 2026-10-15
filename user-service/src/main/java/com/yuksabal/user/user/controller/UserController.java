package com.yuksabal.user.user.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.user.global.swagger.ApiErrorCodes;
import com.yuksabal.user.user.dto.request.UserSearchRequest;
import com.yuksabal.user.user.dto.response.UserResponse;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.user.service.UserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.UNAUTHORIZED;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 인증 필요: 토큰 오류 401은 게이트웨이가 응답하고, X-User-Id·X-User-Role은 게이트웨이가 검증된 JWT 값으로 넣는다
@Tag(name = "User", description = "회원 관리 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;

    // 검색 조건은 권한 확인 뒤 서비스에서 검증한다 (MASTER가 아니면 조건과 무관하게 403)
    @Operation(summary = "회원 목록 조회 (MASTER)",
            description = "검색: username(부분 일치), role, hubId / 정렬 가능: createdAt, username")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN})
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getUsers(
            @ParameterObject UserSearchRequest request,
            @ParameterObject Pageable pageable,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                userQueryService.getUsers(requesterId, UserRole.from(role), request, pageable),
                "목록을 조회했습니다."));
    }

    @Operation(summary = "회원 상세 조회 (MASTER 또는 본인)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND})
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(
            @PathVariable UUID userId,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                userQueryService.getUser(requesterId, UserRole.from(role), userId),
                "회원을 조회했습니다."));
    }
}
