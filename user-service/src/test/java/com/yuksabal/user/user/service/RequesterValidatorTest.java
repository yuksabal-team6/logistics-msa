package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// USE-12 비활성 회원 접근 제한: 회원 관리·조회 API의 요청자 확인
// 토큰이 아직 유효해도 DB의 활성 여부와 역할로 다시 판단한다
class RequesterValidatorTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RequesterValidator validator = new RequesterValidator(userRepository);

    @Test
    void 활성_MASTER는_통과한다() {
        // given
        User master = user(UserRole.MASTER, true);

        // when
        User verified = validator.verifyMaster(master.getId(), UserRole.MASTER);

        // then
        assertThat(verified).isSameAs(master);
    }

    @Test
    void 비활성_MASTER는_403_ACCOUNT_DISABLED() {
        // given
        User master = user(UserRole.MASTER, false);

        // when & then
        assertThatThrownBy(() -> validator.verifyMaster(master.getId(), UserRole.MASTER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    void 비활성이면서_역할도_다르면_비활성을_먼저_알려준다() {
        // given
        User user = user(UserRole.DELIVERY, false);

        // when & then
        assertThatThrownBy(() -> validator.verify(user.getId(), UserRole.USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    void 토큰_역할과_DB_역할이_다르면_403() {
        // given
        // 역할을 바꾼 뒤에도 예전 토큰(USER)이 남아 있는 경우
        User user = user(UserRole.DELIVERY, true);

        // when & then
        assertThatThrownBy(() -> validator.verify(user.getId(), UserRole.USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void MASTER가_아니면_관리_API는_403() {
        // given
        User user = user(UserRole.USER, true);

        // when & then
        assertThatThrownBy(() -> validator.verifyMaster(user.getId(), UserRole.USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 탈퇴했거나_없는_요청자는_403() {
        // given
        UUID deleted = UUID.randomUUID();
        given(userRepository.findById(deleted)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> validator.verifyMaster(deleted, UserRole.MASTER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    private User user(UserRole role, boolean active) {
        User user = User.builder().username("user01").password("$2a$hash").email("user01@example.com")
                .slackId("U1").role(role).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.changeActive(active);
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        return user;
    }
}
