package com.yuksabal.user.global.init;

import com.yuksabal.user.global.config.InitialMasterProperties;
import com.yuksabal.user.user.dto.request.RegisterRequest;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 최초 MASTER 계정 생성. 회원가입 API와 분리되어 있고, 일반 회원가입은 계속 USER로 고정된다.
// - 환경변수 4개가 모두 있을 때만 시도, 하나라도 비면 변수 이름만 로그로 안내하고 건너뛴다
// - 삭제되지 않은 MASTER가 이미 있으면 만들지 않는다 (재시작해도 중복 생성 없음)
// - 어떤 경우에도 예외를 밖으로 던지지 않아 서비스 기동을 막지 않는다. 비밀번호는 로그에 남기지 않는다
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialMasterInitializer implements ApplicationRunner {

    // 검증 실패 필드 → 환경변수 이름 (회원가입과 같은 형식 규칙을 적용한다)
    private static final Map<String, String> VARIABLE_NAMES = Map.of(
            "username", "INITIAL_MASTER_USERNAME",
            "password", "INITIAL_MASTER_PASSWORD",
            "email", "INITIAL_MASTER_EMAIL",
            "slackId", "INITIAL_MASTER_SLACK_ID");

    private final InitialMasterProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    @Override
    public void run(ApplicationArguments args) {
        try {
            createIfAbsent();
        } catch (RuntimeException e) {
            // DB 오류 등. 원인 메시지에 비밀번호는 담기지 않는다 (해시 전 값은 저장 쿼리에 쓰이지 않음)
            log.error("초기 MASTER 계정 생성 중 오류가 발생해 건너뜁니다.", e);
        }
    }

    void createIfAbsent() {
        List<String> missing = properties.missingVariables();
        if (missing.size() == VARIABLE_NAMES.size()) {
            log.info("초기 MASTER 환경변수가 설정되지 않아 계정 생성을 건너뜁니다.");
            return;
        }
        if (!missing.isEmpty()) {
            log.warn("초기 MASTER 계정 생성을 건너뜁니다. 비어 있는 환경변수: {}", missing);
            return;
        }

        List<String> invalid = invalidVariables();
        if (!invalid.isEmpty()) {
            log.warn("초기 MASTER 계정 생성을 건너뜁니다. 회원가입 형식 규칙에 맞지 않는 환경변수: {}", invalid);
            return;
        }

        if (userRepository.existsByRole(UserRole.MASTER)) {
            log.info("MASTER 계정이 이미 있어 초기 MASTER 생성을 건너뜁니다.");
            return;
        }
        if (userRepository.existsByUsername(properties.username()) || userRepository.existsByEmail(properties.email())) {
            log.warn("초기 MASTER 계정 생성을 건너뜁니다. 같은 아이디 또는 이메일의 회원이 이미 있습니다.");
            return;
        }

        User master = User.builder()
                .username(properties.username())
                .password(passwordEncoder.encode(properties.password()))
                .email(properties.email())
                .slackId(properties.slackId())
                .role(UserRole.MASTER)
                .build();
        try {
            userRepository.saveAndFlush(master);
        } catch (DataIntegrityViolationException e) {
            // 여러 인스턴스가 동시에 기동해 부분 유니크 인덱스(#15)에 걸린 경우
            log.warn("초기 MASTER 계정이 동시에 생성되어 건너뜁니다.");
            return;
        }
        log.info("초기 MASTER 계정을 생성했습니다. username={}", properties.username());
    }

    // 회원가입 요청과 같은 규칙(아이디·비밀번호 정책·이메일·Slack ID 길이)으로 검사하고, 틀린 변수 이름만 돌려준다
    private List<String> invalidVariables() {
        RegisterRequest asRegister = new RegisterRequest(
                properties.username(), properties.password(), properties.slackId(), properties.email(), null, null);
        return validator.validate(asRegister).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .map(VARIABLE_NAMES::get)
                .distinct()
                .sorted()
                .toList();
    }
}
