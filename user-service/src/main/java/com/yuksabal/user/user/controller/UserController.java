package com.yuksabal.user.user.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.user.global.swagger.ApiErrorCodes;
import com.yuksabal.user.user.dto.request.AdminUpdateUserRequest;
import com.yuksabal.user.user.dto.request.ChangeRoleRequest;
import com.yuksabal.user.user.dto.request.ChangeStatusRequest;
import com.yuksabal.user.user.dto.request.UserSearchRequest;
import com.yuksabal.user.user.dto.response.UserResponse;
import com.yuksabal.user.user.service.UserAdminService;
import com.yuksabal.user.user.service.UserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import static com.yuksabal.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE;
import static com.yuksabal.common.exception.CommonErrorCode.UNAUTHORIZED;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.user.global.exception.UserErrorCode.ACCOUNT_DISABLED;
import static com.yuksabal.user.global.exception.UserErrorCode.USER_HAS_ACTIVE_WORK;

// 인증 필요: 토큰 오류 401은 게이트웨이가 응답하고, X-User-Id·X-User-Role은 게이트웨이가 검증된 JWT 값으로 넣는다
@Tag(name = "User", description = "회원 관리 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;
    private final UserAdminService userAdminService;

    // 검색 조건은 권한 확인 뒤 서비스에서 검증한다 (MASTER가 아니면 조건과 무관하게 403)
    @Operation(summary = "회원 목록 조회 (MASTER)",
            description = "검색: username(부분 일치), role, hubId / 정렬 가능: createdAt, username")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN}, value = ACCOUNT_DISABLED)
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
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND}, value = ACCOUNT_DISABLED)
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(
            @PathVariable UUID userId,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                userQueryService.getUser(requesterId, UserRole.from(role), userId),
                "회원을 조회했습니다."));
    }

    @Operation(summary = "회원 정보 수정 (MASTER)",
            description = "보낸 필드만 수정한다. 수정 가능: username, email, slackId, isPublic, hubId, companyId "
                    + "(비밀번호·역할·계정 상태는 수정 불가, 요청에 있어도 무시). "
                    + "hubId·companyId가 바뀌면 내부 API로 존재를 확인한다 (없거나 삭제됨 400, 확인 실패 502). "
                    + "업체 관리자인 COMPANY 회원의 companyId는 관리 업체로만 바꿀 수 있다 (422)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND, DUPLICATE_RESOURCE,
            BUSINESS_RULE_VIOLATION, DEPENDENCY_FAILURE}, value = ACCOUNT_DISABLED)
    @PatchMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUpdateUserRequest request,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                userAdminService.updateUser(requesterId, UserRole.from(role), userId, request),
                "회원 정보가 수정되었습니다."));
    }

    @Operation(summary = "회원 역할 변경 (MASTER)",
            description = "자기 자신·마지막 MASTER·업체 관리자(COMPANY → 다른 역할)는 변경 불가(422), "
                    + "같은 역할이면 변경 없이 200. DELIVERY → 다른 역할은 진행 중인 배송이 있으면 409. "
                    + "업체 관리자·배송 확인 실패 502. 대상은 다시 로그인해야 새 역할이 토큰에 반영된다")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION,
            DEPENDENCY_FAILURE}, value = {ACCOUNT_DISABLED, USER_HAS_ACTIVE_WORK})
    @PatchMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<UserResponse>> changeRole(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeRoleRequest request,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                userAdminService.changeRole(requesterId, UserRole.from(role), userId, request),
                "회원 역할이 변경되었습니다."));
    }

    @Operation(summary = "계정 활성화·비활성화 (MASTER)",
            description = "자기 자신·마지막 MASTER는 비활성화 불가(422), 같은 상태면 변경 없이 200. "
                    + "진행 중인 배송이 있으면 비활성화 불가(409), 배송 확인 실패 502. "
                    + "비활성 계정은 로그인과 user-service API가 403 ACCOUNT_DISABLED")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION,
            DEPENDENCY_FAILURE}, value = {ACCOUNT_DISABLED, USER_HAS_ACTIVE_WORK})
    @PatchMapping("/{userId}/status")
    public ResponseEntity<ApiResponse<UserResponse>> changeStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeStatusRequest request,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                userAdminService.changeStatus(requesterId, UserRole.from(role), userId, request),
                "계정 상태가 변경되었습니다."));
    }

    // 삭제: 200 + ApiResponse.success(null, message). 소프트 삭제(deleted_at·deleted_by), 응답 data 없음
    @Operation(summary = "회원 삭제 (MASTER)",
            description = "소프트 삭제. 자기 자신·마지막 MASTER는 422, 진행 중인 업무가 있으면 409, "
                    + "업무 관계(업체 관리자·주문·배송)를 확인하지 못하면 502(상대 서비스 오류)·503(확인 기능 미준비)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION,
            DEPENDENCY_FAILURE, SERVICE_UNAVAILABLE}, value = {ACCOUNT_DISABLED, USER_HAS_ACTIVE_WORK})
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable UUID userId,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestHeader("X-User-Role") String role) {
        userAdminService.deleteUser(requesterId, UserRole.from(role), userId);
        return ResponseEntity.ok(ApiResponse.success(null, "회원이 삭제되었습니다."));
    }
}
