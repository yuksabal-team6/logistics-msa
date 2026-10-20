package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// USE-16 소프트 삭제 및 삭제 제한: 삭제·비활성화의 DB 쓰기 단계 (UserRepository·EntityManager Mock)
// 진행 중 업무가 있으면 DB 트랜잭션을 되돌리는 대신, 먼저 커밋한 비활성화(hold)를 restore로 되돌린다
// updated_at은 JPA Auditing이 채우므로 단위 테스트에서는 직접 넣는다
class UserStateTransitionTest {

    private static final LocalDateTime HELD_AT = LocalDateTime.of(2026, 10, 20, 12, 0);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final UserStateTransition transition = new UserStateTransition(userRepository, entityManager);

    private final User user = user();
    private final UUID masterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.of(user));
    }

    // ---------- hold: 확인 전에 먼저 비활성화 ----------

    @Test
    void 활성_회원을_hold하면_비활성으로_저장하고_되돌릴_대상으로_표시한다() {
        // given
        updatedAt(HELD_AT);

        // when
        UserStateTransition.Hold hold = transition.hold(user.getId());

        // then
        assertThat(user.isActive()).isFalse();
        assertThat(hold.deactivated()).isTrue();
        assertThat(hold.updatedAt()).isEqualTo(HELD_AT);
        verify(userRepository).flush();
        verify(entityManager).refresh(user);
    }

    @Test
    void 원래_비활성이던_회원은_hold해도_되돌릴_것이_없다() {
        // given
        user.changeActive(false);

        // when
        UserStateTransition.Hold hold = transition.hold(user.getId());

        // then
        assertThat(hold.deactivated()).isFalse();
        verify(userRepository, never()).flush();
    }

    // ---------- delete: 소프트 삭제 ----------

    @Test
    void 확인을_통과하면_삭제_시각과_삭제한_MASTER를_기록한다() {
        // given
        UserStateTransition.Hold hold = held();

        // when
        transition.delete(hold, masterId);

        // then
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getDeletedBy()).isEqualTo(masterId);
        assertThat(user.isDeleted()).isTrue();
        // 행은 지우지 않는다 (repository.delete 금지)
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void 확인하는_동안_다른_변경이_있었으면_삭제하지_않고_422() {
        // given
        UserStateTransition.Hold hold = held();
        updatedAt(HELD_AT.plusSeconds(1));

        // when & then
        assertThatThrownBy(() -> transition.delete(hold, masterId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(user.getDeletedAt()).isNull();
    }

    @Test
    void 확정_직전에_회원이_사라졌으면_404() {
        // given
        UserStateTransition.Hold hold = held();
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> transition.delete(hold, masterId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---------- restore: 막혔을 때 비활성화 되돌리기 ----------

    @Test
    void 막히면_hold한_비활성화를_되돌려_다시_활성이_된다() {
        // given
        UserStateTransition.Hold hold = held();

        // when
        transition.restore(hold);

        // then
        assertThat(user.isActive()).isTrue();
        assertThat(user.getDeletedAt()).isNull();
    }

    @Test
    void 확인하는_동안_다른_MASTER가_바꿨으면_덮어쓰지_않고_비활성으로_둔다() {
        // given
        UserStateTransition.Hold hold = held();
        updatedAt(HELD_AT.plusSeconds(1));

        // when
        transition.restore(hold);

        // then
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void 원래_비활성이던_회원은_되돌리지_않는다() {
        // given
        UserStateTransition.Hold hold = new UserStateTransition.Hold(user.getId(), false, HELD_AT);
        user.changeActive(false);

        // when
        transition.restore(hold);

        // then
        assertThat(user.isActive()).isFalse();
        verify(userRepository, never()).findByIdForUpdate(any());
    }

    // hold 직후 상태: 비활성, updated_at = HELD_AT
    private UserStateTransition.Hold held() {
        updatedAt(HELD_AT);
        return transition.hold(user.getId());
    }

    private void updatedAt(LocalDateTime value) {
        ReflectionTestUtils.setField(user, "updatedAt", value);
    }

    private static User user() {
        User user = User.builder().username("driver01").password("$2a$hash").email("driver01@example.com")
                .slackId("U1").role(UserRole.DELIVERY).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
