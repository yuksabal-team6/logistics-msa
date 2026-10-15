package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.global.jwt.JwtProvider;
import com.yuksabal.user.user.dto.request.ChangePasswordRequest;
import com.yuksabal.user.user.dto.request.LoginRequest;
import com.yuksabal.user.user.dto.request.RegisterRequest;
import com.yuksabal.user.user.dto.request.UpdateMeRequest;
import com.yuksabal.user.user.dto.response.LoginResponse;
import com.yuksabal.user.user.dto.response.MeResponse;
import com.yuksabal.user.user.dto.response.RegisterResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final String DUPLICATE_MESSAGE = "이미 사용 중인 아이디 또는 이메일입니다.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    // 없는 아이디도 BCrypt 비교를 한 번 수행해 응답 시간으로 가입 여부를 알 수 없게 한다
    private final String dummyPasswordHash;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // 역할은 항상 USER. 허브·업체 존재 확인은 내부 API(#7, #2) 준비 후 추가한다.
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        validateNotDuplicated(request);

        User user = User.builder()
                .username(request.username())
                .slackId(request.slackId())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .hubId(request.hubId())
                .companyId(request.companyId())
                .role(UserRole.USER)
                .build();

        // 동시 가입으로 중복 확인을 함께 통과하면 DB 부분 유니크 인덱스(#15)가 막고
        // common 핸들러가 유니크 위반(23505)만 409, 그 외 제약 위반은 500으로 응답한다
        return RegisterResponse.from(userRepository.saveAndFlush(user));
    }

    // 아이디 없음·비밀번호 불일치·삭제된 회원 모두 같은 401로 응답한다
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username()).orElse(null);
        String passwordHash = user != null ? user.getPassword() : dummyPasswordHash;

        if (!passwordEncoder.matches(request.password(), passwordHash) || user == null) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        // 비밀번호가 맞을 때만 비활성 여부를 알려준다 (틀리면 위에서 401로 끝나 계정 상태가 드러나지 않는다)
        if (!user.isActive()) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }

        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole());
        return LoginResponse.bearer(accessToken, jwtProvider.getAccessTokenExpirationSeconds());
    }

    // userId는 게이트웨이가 검증된 JWT에서 넣어 준 X-User-Id 값이다 (클라이언트가 보낸 값은 게이트웨이가 지운다)
    @Transactional(readOnly = true)
    public MeResponse getMe(UUID userId) {
        return MeResponse.from(findActiveUser(userId));
    }

    @Transactional
    public MeResponse updateMe(UUID userId, UpdateMeRequest request) {
        if (request.hasNoChanges()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "수정할 항목이 없습니다.");
        }
        User user = findActiveUser(userId);
        validateNotDuplicatedForUpdate(userId, request);

        user.updateProfile(request.username(), request.email(), request.slackId(), request.isPublic());
        // 동시 수정으로 중복 확인을 함께 통과하면 부분 유니크 인덱스(#15)가 막고 common 핸들러가 409로 응답한다
        userRepository.flush();
        return MeResponse.from(user);
    }

    // 현재 비밀번호 불일치 401, 새 비밀번호 정책 위반(형식은 DTO, 기존과 동일 여부는 여기서) 400
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = findActiveUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BusinessException(UserErrorCode.INVALID_PASSWORD);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "새 비밀번호는 현재 비밀번호와 달라야 합니다.", "newPassword");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    // 토큰은 유효하지만 탈퇴(소프트 삭제)한 회원이면 404 — 토큰 무효화가 없어 생길 수 있다
    // 비활성 계정은 토큰이 아직 유효해도 403 ACCOUNT_DISABLED (#67)
    private User findActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않거나 탈퇴한 회원입니다."));
        if (!user.isActive()) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        return user;
    }

    // 본인을 뺀 다른 회원과 아이디·이메일이 겹치면 409. 본인의 현재 값을 그대로 보내는 것은 허용한다
    private void validateNotDuplicatedForUpdate(UUID userId, UpdateMeRequest request) {
        List<ApiErrorDetail> details = new ArrayList<>();
        if (request.username() != null && userRepository.existsByUsernameAndIdNot(request.username(), userId)) {
            details.add(new ApiErrorDetail("username", "이미 사용 중인 아이디입니다."));
        }
        if (request.email() != null && userRepository.existsByEmailAndIdNot(request.email(), userId)) {
            details.add(new ApiErrorDetail("email", "이미 사용 중인 이메일입니다."));
        }
        if (!details.isEmpty()) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE, DUPLICATE_MESSAGE, details);
        }
    }

    // 삭제되지 않은 회원과 아이디·이메일이 겹치면 409. 둘 다 겹치면 둘 다 details로 알려준다
    private void validateNotDuplicated(RegisterRequest request) {
        List<ApiErrorDetail> details = new ArrayList<>();
        if (userRepository.existsByUsername(request.username())) {
            details.add(new ApiErrorDetail("username", "이미 사용 중인 아이디입니다."));
        }
        if (userRepository.existsByEmail(request.email())) {
            details.add(new ApiErrorDetail("email", "이미 사용 중인 이메일입니다."));
        }
        if (!details.isEmpty()) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE, DUPLICATE_MESSAGE, details);
        }
    }
}
