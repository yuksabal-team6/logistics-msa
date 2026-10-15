package com.yuksabal.user.user.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.global.swagger.ApiErrorCodes;
import com.yuksabal.user.user.dto.request.LoginRequest;
import com.yuksabal.user.user.dto.request.RegisterRequest;
import com.yuksabal.user.user.dto.response.LoginResponse;
import com.yuksabal.user.user.dto.response.RegisterResponse;
import com.yuksabal.user.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.user.global.exception.UserErrorCode.INVALID_CREDENTIALS;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    // 공개 API (게이트웨이 auth.public-paths)
    @Operation(summary = "회원가입", description = "가입 즉시 USER 역할로 활성화된다.")
    @SecurityRequirements
    @ApiErrorCodes(common = {VALIDATION_ERROR, DUPLICATE_RESOURCE})
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(userService.register(request), "회원가입이 완료되었습니다."));
    }

    // 공개 API (게이트웨이 auth.public-paths)
    @Operation(summary = "로그인", description = "아이디·비밀번호가 맞으면 Access Token(1시간)을 발급한다.")
    @SecurityRequirements
    @ApiErrorCodes(common = VALIDATION_ERROR, value = INVALID_CREDENTIALS)
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.login(request), "로그인에 성공했습니다."));
    }
}
