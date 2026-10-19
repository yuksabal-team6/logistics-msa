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

    private static final String USERNAME = "user01";
    private static final String EMAIL = "user01@example.com";
    private static final String RAW_PASSWORD = "Strong123!";
    private static final String WRONG_PASSWORD = "Wrong123!";
    private static final String NEW_PASSWORD = "Newpass123!";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String jwtSecret = TestJwt.randomBase64Secret(32);
    private final AffiliationValidator affiliationValidator = mock(AffiliationValidator.class);
    private final UserService userService = new UserService(userRepository, passwordEncoder,
            new JwtProvider(TestJwt.properties(jwtSecret)), affiliationValidator);

    private final UUID hubId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final RegisterRequest request =
            new RegisterRequest(USERNAME, RAW_PASSWORD, "U12345678", EMAIL, hubId, companyId);

    @BeforeEach
    void setUp() {
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 회원가입하면_BCrypt로_암호화하고_USER로_저장한다() {
        // when
        RegisterResponse response = userService.register(request);

        // then
        User saved = capturedSavedUser();
        assertThat(saved.getPassword()).isNotEqualTo(RAW_PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getPassword())).isTrue();
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getHubId()).isEqualTo(hubId);
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.isPublic()).isTrue();
        assertThat(response.username()).isEqualTo(USERNAME);
        assertThat(response.role()).isEqualTo("USER");
    }

    @Test
    void 소속_없이도_가입할_수_있다() {
        // given
        RegisterRequest noAffiliation =
                new RegisterRequest("user02", RAW_PASSWORD, "U1", "user02@example.com", null, null);

        // when
        userService.register(noAffiliation);

        // then
        User saved = capturedSavedUser();
        assertThat(saved.getHubId()).isNull();
        assertThat(saved.getCompanyId()).isNull();
    }

    @Test
    void 아이디가_중복이면_409() {
        // given
        given(userRepository.existsByUsername(USERNAME)).willReturn(true);

        // when & then
        assertDuplicated("username");
    }

    @Test
    void 이메일이_중복이면_409() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertDuplicated("email");
    }

    @Test
    void 아이디와_이메일이_모두_중복이면_둘_다_알려준다() {
        // given
        given(userRepository.existsByUsername(USERNAME)).willReturn(true);
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertDuplicated("username", "email");
    }

    @Test
    void DB_제약_위반은_변환하지_않고_공통_핸들러로_넘긴다() {
        // given
        // 23505만 409로 바꾸는 판단은 common GlobalExceptionHandler가 한다
        DataIntegrityViolationException violation = new DataIntegrityViolationException("dup");
        given(userRepository.saveAndFlush(any(User.class))).willThrow(violation);

        // when & then
        assertThatThrownBy(() -> userService.register(request)).isSameAs(violation);
    }

    @Test
    void 로그인하면_회원_ID와_역할을_담은_토큰을_발급한다() {
        // given
        UUID userId = UUID.randomUUID();
        given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(savedUser(userId, UserRole.DELIVERY)));

        // when
        LoginResponse response = userService.login(new LoginRequest(USERNAME, RAW_PASSWORD));

        // then
        Claims claims = TestJwt.parse(jwtSecret, response.accessToken()).getPayload();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role")).isEqualTo("DELIVERY");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
    }

    @Test
    void 비밀번호가_틀리면_401() {
        // given
        given(userRepository.findByUsername(USERNAME))
                .willReturn(Optional.of(savedUser(UUID.randomUUID(), UserRole.USER)));

        // when & then
        assertInvalidCredentials(new LoginRequest(USERNAME, WRONG_PASSWORD));
    }

    @Test
    void 아이디가_없으면_비밀번호_불일치와_같은_401() {
        // given
        given(userRepository.findByUsername("nobody")).willReturn(Optional.empty());

        // when & then
        assertInvalidCredentials(new LoginRequest("nobody", RAW_PASSWORD));
    }

    @Test
    void 내_정보_조회_시_회원이_없으면_404() {
        // given
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getMe(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 수정할_항목이_없으면_조회하지_않고_400() {
        // given
        UpdateMeRequest emptyRequest = new UpdateMeRequest(null, null, null, null);

        // when & then
        assertThatThrownBy(() -> userService.updateMe(UUID.randomUUID(), emptyRequest))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.VALIDATION_ERROR);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void 비밀번호_변경은_현재_비밀번호가_맞으면_새_해시로_바꾼다() {
        // given
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, UserRole.USER);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        // when
        userService.changePassword(userId, new ChangePasswordRequest(RAW_PASSWORD, NEW_PASSWORD));

        // then
        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPassword())).isTrue();
        assertThat(passwordEncoder.matches(RAW_PASSWORD, user.getPassword())).isFalse();
    }

    @Test
    void 현재_비밀번호가_틀리면_401_INVALID_PASSWORD이고_바뀌지_않는다() {
        // given
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, UserRole.USER);
        String passwordBefore = user.getPassword();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        ChangePasswordRequest wrongCurrent = new ChangePasswordRequest(WRONG_PASSWORD, NEW_PASSWORD);

        // when & then
        assertThatThrownBy(() -> userService.changePassword(userId, wrongCurrent))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.INVALID_PASSWORD);
        assertThat(user.getPassword()).isEqualTo(passwordBefore);
    }

    @Test
    void 새_비밀번호가_현재와_같으면_400() {
        // given
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.of(savedUser(userId, UserRole.USER)));
        ChangePasswordRequest samePassword = new ChangePasswordRequest(RAW_PASSWORD, RAW_PASSWORD);

        // when & then
        assertThatThrownBy(() -> userService.changePassword(userId, samePassword))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(e.getDetails()).extracting(ApiErrorDetail::field).containsExactly("newPassword");
                });
    }

    private User savedUser(UUID userId, UserRole role) {
        User user = User.builder()
                .username(USERNAME)
                .slackId("U12345678")
                .email(EMAIL)
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .role(role)
                .build();
        // id는 JPA가 저장할 때 채우므로 단위 테스트에서는 직접 넣는다
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private User capturedSavedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        return captor.getValue();
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
