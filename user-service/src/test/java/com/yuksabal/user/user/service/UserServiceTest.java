package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.global.jwt.JwtProvider;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.dto.request.ChangePasswordRequest;
import com.yuksabal.user.user.dto.request.LoginRequest;
import com.yuksabal.user.user.dto.request.RegisterRequest;
import com.yuksabal.user.user.dto.request.UpdateMeRequest;
import com.yuksabal.user.user.dto.response.LoginResponse;
import com.yuksabal.user.user.dto.response.RegisterResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserServiceTest {

    private static final String RAW_PASSWORD = "Strong123!";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String jwtSecret = TestJwt.randomBase64Secret(32);
    private final AffiliationValidator affiliationValidator = mock(AffiliationValidator.class);
    private final UserService userService = new UserService(userRepository, passwordEncoder,
            new JwtProvider(TestJwt.properties(jwtSecret)), affiliationValidator);

    private final UUID hubId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final RegisterRequest request =
            new RegisterRequest("user01", RAW_PASSWORD, "U12345678", "user01@example.com", hubId, companyId);

    @BeforeEach
    void setUp() {
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 회원가입하면_BCrypt로_암호화하고_USER로_저장한다() {
        RegisterResponse response = userService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getPassword()).isNotEqualTo(RAW_PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getPassword())).isTrue();
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getHubId()).isEqualTo(hubId);
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.isPublic()).isTrue();
        assertThat(response.username()).isEqualTo("user01");
        assertThat(response.role()).isEqualTo("USER");
    }

    @Test
    void 소속_없이도_가입할_수_있다() {
        userService.register(new RegisterRequest("user02", RAW_PASSWORD, "U1", "user02@example.com", null, null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getHubId()).isNull();
        assertThat(captor.getValue().getCompanyId()).isNull();
    }

    @Test
    void 아이디가_중복이면_409() {
        given(userRepository.existsByUsername("user01")).willReturn(true);

        assertDuplicated("username");
    }

    @Test
    void 이메일이_중복이면_409() {
        given(userRepository.existsByEmail("user01@example.com")).willReturn(true);

        assertDuplicated("email");
    }

    @Test
    void 아이디와_이메일이_모두_중복이면_둘_다_알려준다() {
        given(userRepository.existsByUsername("user01")).willReturn(true);
        given(userRepository.existsByEmail("user01@example.com")).willReturn(true);

        assertDuplicated("username", "email");
    }

    @Test
    void DB_제약_위반은_변환하지_않고_공통_핸들러로_넘긴다() {
        // 23505만 409로 바꾸는 판단은 common GlobalExceptionHandler가 한다
        DataIntegrityViolationException violation = new DataIntegrityViolationException("dup");
        given(userRepository.saveAndFlush(any(User.class))).willThrow(violation);

        assertThatThrownBy(() -> userService.register(request)).isSameAs(violation);
    }

    @Test
    void 로그인하면_회원_ID와_역할을_담은_토큰을_발급한다() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findByUsername("user01")).willReturn(Optional.of(savedUser(userId, UserRole.DELIVERY)));

        LoginResponse response = userService.login(new LoginRequest("user01", RAW_PASSWORD));

        Claims claims = TestJwt.parse(jwtSecret, response.accessToken()).getPayload();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role")).isEqualTo("DELIVERY");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
    }

    @Test
    void 비밀번호가_틀리면_401() {
        given(userRepository.findByUsername("user01")).willReturn(Optional.of(savedUser(UUID.randomUUID(), UserRole.USER)));

        assertInvalidCredentials(new LoginRequest("user01", "Wrong123!"));
    }

    @Test
    void 아이디가_없으면_비밀번호_불일치와_같은_401() {
        given(userRepository.findByUsername("nobody")).willReturn(Optional.empty());

        assertInvalidCredentials(new LoginRequest("nobody", RAW_PASSWORD));
    }

    @Test
    void 내_정보_조회_시_회원이_없으면_404() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMe(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 수정할_항목이_없으면_조회하지_않고_400() {
        assertThatThrownBy(() -> userService.updateMe(UUID.randomUUID(), new UpdateMeRequest(null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.VALIDATION_ERROR);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void 비밀번호_변경은_현재_비밀번호가_맞으면_새_해시로_바꾼다() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, UserRole.USER);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        userService.changePassword(userId, new ChangePasswordRequest(RAW_PASSWORD, "Newpass123!"));

        assertThat(passwordEncoder.matches("Newpass123!", user.getPassword())).isTrue();
        assertThat(passwordEncoder.matches(RAW_PASSWORD, user.getPassword())).isFalse();
    }

    @Test
    void 현재_비밀번호가_틀리면_401_INVALID_PASSWORD이고_바뀌지_않는다() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, UserRole.USER);
        String before = user.getPassword();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.changePassword(userId, new ChangePasswordRequest("Wrong123!", "Newpass123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.INVALID_PASSWORD);
        assertThat(user.getPassword()).isEqualTo(before);
    }

    @Test
    void 새_비밀번호가_현재와_같으면_400() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.of(savedUser(userId, UserRole.USER)));

        assertThatThrownBy(() -> userService.changePassword(userId, new ChangePasswordRequest(RAW_PASSWORD, RAW_PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(e.getDetails()).extracting(ApiErrorDetail::field).containsExactly("newPassword");
                });
    }

    private User savedUser(UUID userId, UserRole role) {
        User user = User.builder()
                .username("user01")
                .slackId("U12345678")
                .email("user01@example.com")
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .role(role)
                .build();
        // id는 JPA가 저장할 때 채우므로 단위 테스트에서는 직접 넣는다
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private void assertInvalidCredentials(LoginRequest loginRequest) {
        assertThatThrownBy(() -> userService.login(loginRequest))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.INVALID_CREDENTIALS);
    }

    private void assertDuplicated(String... fields) {
        assertThatThrownBy(() -> userService.register(request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.DUPLICATE_RESOURCE);
                    assertThat(e.getDetails()).extracting(ApiErrorDetail::field).containsExactly(fields);
                });
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }
}
