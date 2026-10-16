package com.yuksabal.user.user.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.global.swagger.ApiErrorCodes;
import com.yuksabal.user.user.dto.request.ChangePasswordRequest;
import com.yuksabal.user.user.dto.request.LoginRequest;
import com.yuksabal.user.user.dto.request.RegisterRequest;
import com.yuksabal.user.user.dto.request.UpdateMeRequest;
import com.yuksabal.user.user.dto.response.LoginResponse;
import com.yuksabal.user.user.dto.response.MeResponse;
import com.yuksabal.user.user.dto.response.RegisterResponse;
import com.yuksabal.user.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.UNAUTHORIZED;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.user.global.exception.UserErrorCode.ACCOUNT_DISABLED;
import static com.yuksabal.user.global.exception.UserErrorCode.INVALID_CREDENTIALS;
import static com.yuksabal.user.global.exception.UserErrorCode.INVALID_PASSWORD;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    // 공개 API (게이트웨이 auth.public-paths)
    @Operation(summary = "회원가입",
            description = "가입 즉시 USER 역할로 활성화된다. hubId·companyId는 내부 API로 존재를 확인한다 "
                    + "(없거나 삭제됨 400, 허브·업체 서비스 확인 실패 502)")
    @SecurityRequirements
    @ApiErrorCodes(common = {VALIDATION_ERROR, DUPLICATE_RESOURCE, DEPENDENCY_FAILURE})
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(userService.register(request), "회원가입이 완료되었습니다."));
    }

    // 공개 API (게이트웨이 auth.public-paths)
    @Operation(summary = "로그인", description = "아이디·비밀번호가 맞으면 Access Token(1시간)을 발급한다.")
    @SecurityRequirements
    @ApiErrorCodes(common = VALIDATION_ERROR, value = {INVALID_CREDENTIALS, ACCOUNT_DISABLED})
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.login(request), "로그인에 성공했습니다."));
    }

    // 이하 인증 필요: 토큰 오류 401은 게이트웨이가 응답하고, X-User-Id는 게이트웨이가 검증된 JWT 값으로 넣는다
    @Operation(summary = "내 정보 조회", description = "비밀번호 등 민감한 정보는 포함하지 않는다.")
    @ApiErrorCodes(common = {UNAUTHORIZED, RESOURCE_NOT_FOUND}, value = ACCOUNT_DISABLED)
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> getMe(@RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(userService.getMe(userId), "내 정보를 조회했습니다."));
    }

    @Operation(summary = "본인 프로필 수정",
            description = "보낸 필드만 수정한다. 수정 가능: username, email, slackId, isPublic")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, RESOURCE_NOT_FOUND, DUPLICATE_RESOURCE}, value = ACCOUNT_DISABLED)
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> updateMe(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody UpdateMeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.updateMe(userId, request), "프로필이 수정되었습니다."));
    }

    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호 확인 후 변경한다. 이미 발급된 토큰은 만료 전까지 유효하다.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, RESOURCE_NOT_FOUND}, value = {INVALID_PASSWORD, ACCOUNT_DISABLED})
    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userId, request);
        return ResponseEntity.ok(ApiResponse.success(null, "비밀번호가 변경되었습니다."));
    }
}
