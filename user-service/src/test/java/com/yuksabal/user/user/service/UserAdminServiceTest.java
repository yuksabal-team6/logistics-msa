package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.dto.request.ChangeRoleRequest;
import com.yuksabal.user.user.dto.request.ChangeStatusRequest;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

// 마지막 활성 MASTER 보호. API로는 자기 자신 변경이 먼저 막혀 이 경로에 거의 닿지 않으므로 단위로 확인한다
// (예: 다른 MASTER가 이미 비활성인 상태에서 요청자 확인과 대상 확인 사이에 상태가 바뀐 경우)
// + 통합 테스트로 만들기 어려운 경우: 확인 구현체가 빠졌을 때(503), 되돌리기 자체가 실패했을 때
class UserAdminServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ActiveWorkChecker noDelivery = checker(ActiveWorkChecker.Target.DELIVERY, List.of());
    private final UserAdminService service = service(
            List.of(noDelivery), new UserStateTransition(userRepository, mock(EntityManager.class)));

    private final User requester = master("master01");
    private final User target = master("master02");

    @BeforeEach
    void setUp() {
        given(userRepository.findById(requester.getId())).willReturn(Optional.of(requester));
        given(userRepository.findById(target.getId())).willReturn(Optional.of(target));
    }

    @Test
    void 활성_MASTER가_한_명뿐이면_강등은_422() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(1L);

        assertThatThrownBy(() -> service.changeRole(requester.getId(), UserRole.MASTER, target.getId(),
                new ChangeRoleRequest("USER")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.getRole()).isEqualTo(UserRole.MASTER);
    }

    @Test
    void 활성_MASTER가_한_명뿐이면_비활성화는_422() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(1L);

        assertThatThrownBy(() -> service.changeStatus(requester.getId(), UserRole.MASTER, target.getId(),
                new ChangeStatusRequest(false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.isActive()).isTrue();
    }

    @Test
    void 활성_MASTER가_둘_이상이면_강등과_비활성화가_된다() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(2L);

        service.changeStatus(requester.getId(), UserRole.MASTER, target.getId(), new ChangeStatusRequest(false));
        assertThat(target.isActive()).isFalse();

        service.changeRole(requester.getId(), UserRole.MASTER, target.getId(), new ChangeRoleRequest("USER"));
        assertThat(target.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void 활성_MASTER가_한_명뿐이면_삭제는_422() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(1L);

        assertThatThrownBy(() -> service.deleteUser(requester.getId(), UserRole.MASTER, target.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.getDeletedAt()).isNull();
    }

    @Test
    void 확인_구현체가_빠져_있으면_503이고_비활성화하지_않는다() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        // company·order 구현체가 없다
        UserAdminService partial = service(List.of(noDelivery), transition);

        assertThatThrownBy(() -> partial.deleteUser(requester.getId(), UserRole.MASTER, target.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        verify(transition, never()).hold(any());
    }

    @Test
    void 되돌리기가_실패하면_그_예외를_던진다() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        UserStateTransition.Hold hold = new UserStateTransition.Hold(target.getId(), true, null);
        given(transition.hold(target.getId())).willReturn(hold);
        willThrow(new DataAccessResourceFailureException("db down")).given(transition).restore(hold);
        ActiveWorkChecker busy = checker(ActiveWorkChecker.Target.DELIVERY,
                List.of(new ActiveWorkChecker.ActiveWork("delivery", "진행 중인 배송이 있습니다.")));
        UserAdminService failing = service(List.of(busy), transition);

        // 409로 응답하면 "바뀐 것 없음"으로 오해한다. 회원은 비활성으로 남았으므로 DB 예외(500)를 그대로 던진다
        assertThatThrownBy(() -> failing.changeStatus(requester.getId(), UserRole.MASTER, target.getId(),
                new ChangeStatusRequest(false)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // 되돌리기가 성공하면 원래 이유(409)를 던진다
        reset(transition);
        given(transition.hold(target.getId())).willReturn(hold);
        assertThatThrownBy(() -> failing.changeStatus(requester.getId(), UserRole.MASTER, target.getId(),
                new ChangeStatusRequest(false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_HAS_ACTIVE_WORK);
        verify(transition).restore(hold);
    }

    private UserAdminService service(List<ActiveWorkChecker> checkers, UserStateTransition transition) {
        return new UserAdminService(userRepository, new RequesterValidator(userRepository),
                mock(AffiliationValidator.class), mock(CompanyOwnerGuard.class), checkers, transition);
    }

    private static ActiveWorkChecker checker(ActiveWorkChecker.Target target, List<ActiveWorkChecker.ActiveWork> work) {
        return new ActiveWorkChecker() {
            @Override
            public Target target() {
                return target;
            }

            @Override
            public List<ActiveWork> findActiveWork(User user) {
                return work;
            }
        };
    }

    private static User master(String username) {
        User user = User.builder().username(username).password("$2a$hash").email(username + "@example.com")
                .slackId("U1").role(UserRole.MASTER).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
