package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.dto.response.UserInternalResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// USE-12 비활성 회원 접근 제한, USE-13 탈퇴 회원 처리: 회원 상세·내부 조회
// 탈퇴(소프트 삭제) 회원은 @SQLRestriction 때문에 findById가 비어 있다 → 여기서는 Optional.empty()로 둔다
class UserQueryServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserQueryService service =
            new UserQueryService(userRepository, new RequesterValidator(userRepository), mock(Validator.class));

    // ---------- 내부 조회 (#76): 비활성과 탈퇴를 구분한다 ----------

    @Test
    void 내부_조회는_비활성_회원을_active_false로_그대로_돌려준다() {
        // given
        User user = user(UserRole.DELIVERY, false);

        // when
        UserInternalResponse response = service.getInternalUser(user.getId());

        // then
        // 비활성 회원을 거절할지는 호출하는 쪽(order 403, delivery·company 422)이 판단한다
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.active()).isFalse();
        assertThat(response.role()).isEqualTo("DELIVERY");
        assertThat(response.slackId()).isEqualTo("U1");
    }

    @Test
    void 내부_조회는_탈퇴_회원이면_404() {
        // given
        UUID deleted = UUID.randomUUID();
        given(userRepository.findById(deleted)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getInternalUser(deleted))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---------- 회원 상세 ----------

    @Test
    void 비활성_회원이_본인을_조회하면_403_ACCOUNT_DISABLED() {
        // given
        User user = user(UserRole.USER, false);

        // when & then
        assertThatThrownBy(() -> service.getUser(user.getId(), UserRole.USER, user.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    void 탈퇴_회원이_예전_토큰으로_본인을_조회하면_404() {
        // given
        UUID deleted = UUID.randomUUID();
        given(userRepository.findById(deleted)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getUser(deleted, UserRole.USER, deleted))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 탈퇴_회원이_예전_토큰으로_다른_회원을_조회하면_403() {
        // given
        UUID deleted = UUID.randomUUID();
        User other = user(UserRole.USER, true);
        given(userRepository.findById(deleted)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getUser(deleted, UserRole.MASTER, other.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void MASTER가_탈퇴_회원을_조회하면_404() {
        // given
        User master = user(UserRole.MASTER, true);
        UUID deleted = UUID.randomUUID();
        given(userRepository.findById(deleted)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getUser(master.getId(), UserRole.MASTER, deleted))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void MASTER는_비활성_회원도_조회할_수_있다() {
        // given
        User master = user(UserRole.MASTER, true);
        User inactive = user(UserRole.USER, false);

        // when & then
        // 다시 활성화하려면 MASTER가 볼 수 있어야 한다
        assertThat(service.getUser(master.getId(), UserRole.MASTER, inactive.getId()).active()).isFalse();
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
