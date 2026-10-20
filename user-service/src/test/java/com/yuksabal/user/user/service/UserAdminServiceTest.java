package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.dto.request.AdminUpdateUserRequest;
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
import static org.mockito.Mockito.verify;

// 마지막 활성 MASTER 보호. API로는 자기 자신 변경이 먼저 막혀 이 경로에 거의 닿지 않으므로 단위로 확인한다
// (예: 다른 MASTER가 이미 비활성인 상태에서 요청자 확인과 대상 확인 사이에 상태가 바뀐 경우)
// + 통합 테스트로 만들기 어려운 경우: 확인 구현체가 빠졌을 때(503), 되돌리기 자체가 실패했을 때
class UserAdminServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AffiliationValidator affiliationValidator = mock(AffiliationValidator.class);
    private final CompanyOwnerGuard companyOwnerGuard = mock(CompanyOwnerGuard.class);
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

    // ---------- 마지막 활성 MASTER 보호 ----------

    @Test
    void 활성_MASTER가_한_명뿐이면_강등은_422() {
        // given
        givenActiveMasterCount(1L);

        // when & then
        assertThatThrownBy(() -> demoteTargetToUser(service))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.getRole()).isEqualTo(UserRole.MASTER);
    }

    @Test
    void 활성_MASTER가_한_명뿐이면_비활성화는_422() {
        // given
        givenActiveMasterCount(1L);

        // when & then
        assertThatThrownBy(() -> deactivateTarget(service))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.isActive()).isTrue();
    }

    @Test
    void 활성_MASTER가_한_명뿐이면_삭제는_422() {
        // given
        givenActiveMasterCount(1L);

        // when & then
        assertThatThrownBy(() -> deleteTarget(service))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.getDeletedAt()).isNull();
    }

    @Test
    void 활성_MASTER가_둘_이상이면_비활성화된다() {
        // given
        givenActiveMasterCount(2L);

        // when
        deactivateTarget(service);

        // then
        assertThat(target.isActive()).isFalse();
    }

    @Test
    void 활성_MASTER가_둘_이상이면_강등된다() {
        // given
        givenActiveMasterCount(2L);

        // when
        demoteTargetToUser(service);

        // then
        assertThat(target.getRole()).isEqualTo(UserRole.USER);
    }

    // ---------- 진행 중 작업 확인 ----------

    @Test
    void 확인_구현체가_빠져_있으면_503이고_비활성화하지_않는다() {
        // given
        givenActiveMasterCount(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        // company·order 구현체가 없다
        UserAdminService partial = service(List.of(noDelivery), transition);

        // when & then
        assertThatThrownBy(() -> deleteTarget(partial))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        verify(transition, never()).hold(any());
    }

    @Test
    void 되돌리기가_실패하면_DB_예외를_그대로_던진다() {
        // given
        givenActiveMasterCount(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        UserStateTransition.Hold hold = new UserStateTransition.Hold(target.getId(), true, null);
        given(transition.hold(target.getId())).willReturn(hold);
        willThrow(new DataAccessResourceFailureException("db down")).given(transition).restore(hold);
        UserAdminService busyService = service(List.of(busyDeliveryChecker()), transition);

        // when & then
        // 409로 응답하면 "바뀐 것 없음"으로 오해한다. 회원은 비활성으로 남았으므로 DB 예외(500)를 그대로 던진다
        assertThatThrownBy(() -> deactivateTarget(busyService))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 되돌리기가_성공하면_원래_이유인_진행_중_작업_409를_던진다() {
        // given
        givenActiveMasterCount(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        UserStateTransition.Hold hold = new UserStateTransition.Hold(target.getId(), true, null);
        given(transition.hold(target.getId())).willReturn(hold);
        UserAdminService busyService = service(List.of(busyDeliveryChecker()), transition);

        // when & then
        assertThatThrownBy(() -> deactivateTarget(busyService))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_HAS_ACTIVE_WORK);
        verify(transition).restore(hold);
    }

    // ---------- USE-16 삭제 순서와 삭제 제한 ----------

    @Test
    void 삭제는_비활성화한_뒤_모든_업무를_확인하고_요청한_MASTER로_기록한다() {
        // given
        givenActiveMasterCount(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        UserStateTransition.Hold hold = new UserStateTransition.Hold(target.getId(), true, null);
        given(transition.hold(target.getId())).willReturn(hold);
        UserAdminService allClear = service(allCheckers(List.of()), transition);

        // when
        deleteTarget(allClear);

        // then
        verify(transition).delete(hold, requester.getId());
        verify(transition, never()).restore(any());
    }

    @Test
    void 진행_중_업무가_있으면_409이고_삭제하지_않고_비활성화를_되돌린다() {
        // given
        givenActiveMasterCount(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        UserStateTransition.Hold hold = new UserStateTransition.Hold(target.getId(), true, null);
        given(transition.hold(target.getId())).willReturn(hold);
        UserAdminService busy = service(allCheckers(
                List.of(new ActiveWorkChecker.ActiveWork("order", "진행 중인 주문이 있습니다."))), transition);

        // when & then
        assertThatThrownBy(() -> deleteTarget(busy))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_HAS_ACTIVE_WORK);
                    assertThat(e.getDetails()).extracting(ApiErrorDetail::field).containsExactly("order");
                });
        verify(transition).restore(hold);
        verify(transition, never()).delete(any(), any());
    }

    @Test
    void 자기_자신_삭제는_비활성화하기_전에_422() {
        // given
        UserStateTransition transition = mock(UserStateTransition.class);
        UserAdminService adminService = service(allCheckers(List.of()), transition);

        // when & then
        assertThatThrownBy(() -> adminService.deleteUser(requester.getId(), UserRole.MASTER, requester.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        verify(transition, never()).hold(any());
    }

    @Test
    void 이미_탈퇴한_회원을_삭제하면_404() {
        // given
        UUID deleted = UUID.randomUUID();
        given(userRepository.findById(deleted)).willReturn(Optional.empty());
        UserStateTransition transition = mock(UserStateTransition.class);
        UserAdminService adminService = service(allCheckers(List.of()), transition);

        // when & then
        assertThatThrownBy(() -> adminService.deleteUser(requester.getId(), UserRole.MASTER, deleted))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(transition, never()).hold(any());
    }

    // ---------- USE-15 다른 서비스 확인 실패 ----------

    @Test
    void 업무_확인_중_다른_서비스가_실패하면_502이고_비활성화를_되돌린다() {
        // given
        givenActiveMasterCount(2L);
        UserStateTransition transition = mock(UserStateTransition.class);
        UserStateTransition.Hold hold = new UserStateTransition.Hold(target.getId(), true, null);
        given(transition.hold(target.getId())).willReturn(hold);
        ActiveWorkChecker failingDelivery = new ActiveWorkChecker() {
            @Override
            public Target target() {
                return Target.DELIVERY;
            }

            @Override
            public List<ActiveWork> findActiveWork(User user) {
                throw new IllegalStateException("delivery-service timeout");
            }
        };
        UserAdminService failing = service(List.of(failingDelivery), transition);

        // when & then
        // 원인 메시지(타임아웃 등)는 응답에 싣지 않는다
        assertThatThrownBy(() -> deactivateTarget(failing))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_FAILURE);
                    assertThat(e.getMessage()).doesNotContain("timeout");
                });
        verify(transition).restore(hold);
    }

    @Test
    void 소속_확인이_실패하면_회원_정보를_바꾸지_않는다() {
        // given
        User member = companyMember();
        UUID newHub = UUID.randomUUID();
        BusinessException hubMissing = new BusinessException(CommonErrorCode.VALIDATION_ERROR, "없는 허브", "hubId");
        willThrow(hubMissing).given(affiliationValidator).validate(newHub, null);
        AdminUpdateUserRequest request = new AdminUpdateUserRequest(null, null, "U99", null, newHub, null);

        // when & then
        assertThatThrownBy(() -> service.updateUser(requester.getId(), UserRole.MASTER, member.getId(), request))
                .isSameAs(hubMissing);
        assertThat(member.getHubId()).isNull();
        assertThat(member.getSlackId()).isEqualTo("U1");
        verify(userRepository, never()).flush();
    }

    // ---------- USE-14 업체 관리자 정합성 (MASTER 회원 수정) ----------

    @Test
    void 업체_관리자의_소속을_다른_업체로_바꾸면_422이고_companyId는_그대로() {
        // given
        User owner = companyMember();
        UUID ownedCompany = owner.getCompanyId();
        UUID otherCompany = UUID.randomUUID();
        willThrow(new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION, "업체 관리자를 먼저 변경해 주세요.", "companyId"))
                .given(companyOwnerGuard).validateCompanyChange(owner, otherCompany);
        AdminUpdateUserRequest request = new AdminUpdateUserRequest(null, null, null, null, null, otherCompany);

        // when & then
        assertThatThrownBy(() -> service.updateUser(requester.getId(), UserRole.MASTER, owner.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(owner.getCompanyId()).isEqualTo(ownedCompany);
    }

    @Test
    void 소속이_그대로면_업체_관리자_확인에_null을_넘긴다() {
        // given
        // 같은 값을 다시 보내면 바뀐 것이 없으므로 company를 조회하지 않게 한다
        User owner = companyMember();
        AdminUpdateUserRequest request =
                new AdminUpdateUserRequest(null, null, null, null, null, owner.getCompanyId());

        // when
        service.updateUser(requester.getId(), UserRole.MASTER, owner.getId(), request);

        // then
        verify(companyOwnerGuard).validateCompanyChange(owner, null);
        verify(affiliationValidator).validate(null, null);
    }

    @Test
    void 업체_관리자_역할_변경이_막히면_역할을_바꾸지_않는다() {
        // given
        givenActiveMasterCount(2L);
        User owner = companyMember();
        UserStateTransition transition = mock(UserStateTransition.class);
        willThrow(new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION, "업체 관리자를 먼저 변경해 주세요.", "role"))
                .given(companyOwnerGuard).validateRoleChange(owner, UserRole.USER);
        UserAdminService adminService = service(List.of(noDelivery), transition);

        // when & then
        assertThatThrownBy(() -> adminService.changeRole(requester.getId(), UserRole.MASTER, owner.getId(),
                new ChangeRoleRequest("USER")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        verify(transition, never()).changeRole(any(UUID.class), any());
        assertThat(owner.getRole()).isEqualTo(UserRole.COMPANY);
    }

    // ---------- helpers ----------

    private void givenActiveMasterCount(long count) {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(count);
    }

    private void demoteTargetToUser(UserAdminService adminService) {
        adminService.changeRole(requester.getId(), UserRole.MASTER, target.getId(), new ChangeRoleRequest("USER"));
    }

    private void deactivateTarget(UserAdminService adminService) {
        adminService.changeStatus(requester.getId(), UserRole.MASTER, target.getId(), new ChangeStatusRequest(false));
    }

    private void deleteTarget(UserAdminService adminService) {
        adminService.deleteUser(requester.getId(), UserRole.MASTER, target.getId());
    }

    private UserAdminService service(List<ActiveWorkChecker> checkers, UserStateTransition transition) {
        return new UserAdminService(userRepository, new RequesterValidator(userRepository),
                affiliationValidator, companyOwnerGuard, checkers, transition);
    }

    // 삭제는 company·order·delivery 모두 확인한다. order 결과만 work로 두고 나머지는 업무 없음
    private static List<ActiveWorkChecker> allCheckers(List<ActiveWorkChecker.ActiveWork> orderWork) {
        return List.of(checker(ActiveWorkChecker.Target.COMPANY, List.of()),
                checker(ActiveWorkChecker.Target.ORDER, orderWork),
                checker(ActiveWorkChecker.Target.DELIVERY, List.of()));
    }

    // A 업체 소속 COMPANY 회원 (허브 없음)
    private User companyMember() {
        User user = User.builder().username("company01").password("$2a$hash").email("company01@example.com")
                .slackId("U1").role(UserRole.COMPANY).companyId(UUID.randomUUID()).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        return user;
    }

    private static ActiveWorkChecker busyDeliveryChecker() {
        return checker(ActiveWorkChecker.Target.DELIVERY,
                List.of(new ActiveWorkChecker.ActiveWork("delivery", "진행 중인 배송이 있습니다.")));
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
