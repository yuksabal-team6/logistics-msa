package com.yuksabal.user.global.init;

import com.yuksabal.user.global.config.InitialMasterProperties;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.entity.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class InitialMasterInitializerTest {

    private static final String PASSWORD = "Master123!";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private InitialMasterInitializer initializer(String username, String password, String email, String slackId) {
        return new InitialMasterInitializer(
                new InitialMasterProperties(username, password, email, slackId), userRepository, passwordEncoder, validator);
    }

    private InitialMasterInitializer complete() {
        return initializer("master01", PASSWORD, "master@example.com", "U0MASTER");
    }

    @Test
    void 네_개가_모두_있고_MASTER가_없으면_BCrypt_해시로_MASTER를_만든다(CapturedOutput output) {
        complete().run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(UserRole.MASTER);
        assertThat(saved.getUsername()).isEqualTo("master01");
        assertThat(saved.getEmail()).isEqualTo("master@example.com");
        assertThat(saved.getSlackId()).isEqualTo("U0MASTER");
        assertThat(saved.getPassword()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPassword())).isTrue();
        assertThat(output).contains("초기 MASTER 계정을 생성했습니다").doesNotContain(PASSWORD);
    }

    @Test
    void 일부가_없으면_건너뛰고_비어_있는_변수_이름만_로그로_알린다(CapturedOutput output) {
        initializer("master01", PASSWORD, null, null).run(null);

        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).existsByRole(any());
        assertThat(output)
                .contains("INITIAL_MASTER_EMAIL", "INITIAL_MASTER_SLACK_ID")
                .doesNotContain("INITIAL_MASTER_USERNAME", "INITIAL_MASTER_PASSWORD")
                .doesNotContain(PASSWORD);
    }

    @Test
    void 빈_문자열이나_공백도_없는_것으로_본다(CapturedOutput output) {
        initializer("master01", "   ", "", "U0MASTER").run(null);

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(output).contains("INITIAL_MASTER_PASSWORD", "INITIAL_MASTER_EMAIL");
    }

    @Test
    void 하나도_없으면_조용히_건너뛴다(CapturedOutput output) {
        initializer(null, null, null, null).run(null);

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(output).contains("초기 MASTER 환경변수가 설정되지 않아");
    }

    @Test
    void MASTER가_이미_있으면_만들지_않는다(CapturedOutput output) {
        given(userRepository.existsByRole(UserRole.MASTER)).willReturn(true);

        complete().run(null);

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(output).contains("MASTER 계정이 이미 있어");
    }

    @Test
    void 회원가입_형식_규칙에_맞지_않으면_건너뛰고_변수_이름만_알린다(CapturedOutput output) {
        initializer("Master!", "weak", "not-an-email", "U0MASTER").run(null);

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(output)
                .contains("INITIAL_MASTER_USERNAME", "INITIAL_MASTER_PASSWORD", "INITIAL_MASTER_EMAIL")
                .doesNotContain("weak");
    }

    @Test
    void 같은_아이디나_이메일의_회원이_있으면_건너뛴다() {
        given(userRepository.existsByUsername("master01")).willReturn(true);

        complete().run(null);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void 동시_생성으로_유니크_인덱스에_걸려도_예외를_던지지_않는다() {
        given(userRepository.saveAndFlush(any(User.class))).willThrow(new DataIntegrityViolationException("dup"));

        assertThatCode(() -> complete().run(null)).doesNotThrowAnyException();
    }

    @Test
    void DB_오류가_나도_기동을_막지_않는다() {
        given(userRepository.existsByRole(UserRole.MASTER)).willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> complete().run(null)).doesNotThrowAnyException();
    }

    @Test
    void 설정_toString에_비밀번호가_없다() {
        assertThat(new InitialMasterProperties("master01", PASSWORD, "m@example.com", "U0").toString())
                .doesNotContain(PASSWORD);
    }
}
