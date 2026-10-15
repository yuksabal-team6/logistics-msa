package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.user.dto.request.RegisterRequest;
import com.yuksabal.user.user.dto.response.RegisterResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.entity.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    private final UserService userService = new UserService(userRepository, passwordEncoder);

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

    private void assertDuplicated(String... fields) {
        assertThatThrownBy(() -> userService.register(request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.DUPLICATE_RESOURCE);
                    assertThat(e.getDetails()).extracting(ApiErrorDetail::field).containsExactly(fields);
                });
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }
}
