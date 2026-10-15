package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.global.jwt.JwtProvider;
import com.yuksabal.user.user.dto.request.LoginRequest;
import com.yuksabal.user.user.dto.request.RegisterRequest;
import com.yuksabal.user.user.dto.response.LoginResponse;
import com.yuksabal.user.user.dto.response.RegisterResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.entity.UserRole;
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

        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole());
        return LoginResponse.bearer(accessToken, jwtProvider.getAccessTokenExpirationSeconds());
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
