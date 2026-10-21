package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.dto.request.AdminUpdateUserRequest;
import com.yuksabal.user.user.dto.request.ChangeRoleRequest;
import com.yuksabal.user.user.dto.request.ChangeStatusRequest;
import com.yuksabal.user.user.dto.response.UserResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.SERVICE_UNAVAILABLE;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// MASTER용 회원 관리: 정보 수정, 역할 변경, 계정 활성화·비활성화 (#67), 삭제 (#68)
// - 요청자는 활성 MASTER여야 한다 (헤더 역할과 DB 역할 대조, RequesterValidator)
// - 대상이 없거나 탈퇴(소프트 삭제)했으면 404. 비활성 회원은 대상이 될 수 있다 (다시 활성화하려면 필요)
// - 자기 자신의 역할 변경·비활성화·삭제, 마지막 활성 MASTER의 강등·비활성화·삭제는 422
// - 이미 같은 역할·상태면 변경 없이 200 (같은 요청을 다시 보내도 결과가 같다)
// - 다른 서비스 확인(Feign)은 DB 연결을 잡지 않도록 트랜잭션 밖에서 하고, DB 쓰기는 UserStateTransition이 한다
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class UserAdminService {

    private static final String DUPLICATE_MESSAGE = "이미 사용 중인 아이디 또는 이메일입니다.";
    // 진행 중 업무 확인 대상 (#68). 삭제는 전부, 비활성화·DELIVERY 역할 변경은 배송만 (delivery·company 답변)
    private static final Set<ActiveWorkChecker.Target> ALL_TARGETS = EnumSet.allOf(ActiveWorkChecker.Target.class);
    private static final Set<ActiveWorkChecker.Target> DELIVERY_ONLY = EnumSet.of(ActiveWorkChecker.Target.DELIVERY);
    // 오류 메시지 "… 수 없습니다."의 앞부분
    private static final String DELETE = "회원을 삭제할";
    private static final String DEACTIVATE = "계정을 비활성화할";
    private static final String ROLE_CHANGE = "역할을 변경할";

    private final UserRepository userRepository;
    private final RequesterValidator requesterValidator;
    private final AffiliationValidator affiliationValidator;
    private final CompanyOwnerGuard companyOwnerGuard;
    private final List<ActiveWorkChecker> activeWorkCheckers;
    private final UserStateTransition stateTransition;

    // 허브·업체는 값이 바뀔 때만 내부 API로 존재를 확인한다 (현재 값과 같으면 호출하지 않음)
    // 업체 관리자(COMPANY)의 companyId는 관리 업체로만 바꿀 수 있다 (422, CompanyOwnerGuard)
    // 확인한 뒤 저장 전에 다른 변경이 끼어들면(역할·소속 변경 등) 확인 결과가 맞지 않으므로 422 (UserStateTransition)
    public UserResponse updateUser(UUID requesterId, UserRole headerRole, UUID userId, AdminUpdateUserRequest request) {
        requesterValidator.verifyMaster(requesterId, headerRole);
        if (request.hasNoChanges()) {
            throw new BusinessException(VALIDATION_ERROR, "수정할 항목이 없습니다.");
        }
        User user = findTarget(userId);
        validateNotDuplicated(userId, request);
        UUID changedCompanyId = changedOrNull(request.companyId(), user.getCompanyId());
        affiliationValidator.validate(changedOrNull(request.hubId(), user.getHubId()), changedCompanyId);
        companyOwnerGuard.validateCompanyChange(user, changedCompanyId);

        return UserResponse.from(stateTransition.update(userId, user.getUpdatedAt(), request));
    }

    // 역할을 바꿔도 대상이 이미 받은 토큰에는 예전 역할이 남아 있다 (토큰 무효화 없음).
    // user-service는 헤더 역할과 DB 역할이 다르면 403이라, 대상은 다시 로그인해야 한다
    // DELIVERY → 다른 역할은 진행 중 배송이 있으면 409 (역할이 바뀌어도 배송 건에 담당자로 남으므로, delivery 답변)
    public UserResponse changeRole(UUID requesterId, UserRole headerRole, UUID userId, ChangeRoleRequest request) {
        requesterValidator.verifyMaster(requesterId, headerRole);
        User user = findTarget(userId);
        UserRole newRole = UserRole.valueOf(request.role());

        if (user.getRole() == newRole) {
            return UserResponse.from(user);
        }
        if (userId.equals(requesterId)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "자기 자신의 역할은 변경할 수 없습니다.");
        }
        if (isLastActiveMaster(user)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "마지막 MASTER의 역할은 변경할 수 없습니다.");
        }
        // 업체 관리자는 COMPANY 역할을 잃으면 안 된다 (422). 내부 API 호출이라 다른 확인 뒤에 한다
        companyOwnerGuard.validateRoleChange(user, newRole);
        if (user.getRole() != UserRole.DELIVERY) {
            return UserResponse.from(stateTransition.changeRole(userId, newRole));
        }

        requireCheckers(DELIVERY_ONLY, ROLE_CHANGE);
        UserStateTransition.Hold hold = stateTransition.hold(userId);
        checkOrRestore(hold, user, DELIVERY_ONLY, ROLE_CHANGE);
        return UserResponse.from(stateTransition.changeRole(hold, newRole));
    }

    // 비활성화해도 대상이 이미 받은 토큰은 만료 전까지 유효하다. user-service API는 DB를 확인해 403으로 막지만,
    // 헤더만 신뢰하는 다른 서비스는 만료(최대 1시간)까지 막지 못한다
    // 진행 중 배송이 있으면 비활성화도 409 (delivery 답변). 주문·업체 관리자는 막지 않는다 (company 답변: 즉시 잠금 필요)
    public UserResponse changeStatus(UUID requesterId, UserRole headerRole, UUID userId, ChangeStatusRequest request) {
        requesterValidator.verifyMaster(requesterId, headerRole);
        User user = findTarget(userId);
        boolean active = request.active();

        if (user.isActive() == active) {
            return UserResponse.from(user);
        }
        if (active) {
            return UserResponse.from(stateTransition.changeActive(userId, true));
        }
        if (userId.equals(requesterId)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "자기 자신의 계정은 비활성화할 수 없습니다.");
        }
        if (isLastActiveMaster(user)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "마지막 MASTER 계정은 비활성화할 수 없습니다.");
        }

        requireCheckers(DELIVERY_ONLY, DEACTIVATE);
        UserStateTransition.Hold hold = stateTransition.hold(userId);
        checkOrRestore(hold, user, DELIVERY_ONLY, DEACTIVATE);
        return UserResponse.from(findTarget(userId));
    }

    // 소프트 삭제: deleted_at·deleted_by(요청한 MASTER) 기록. 이미 삭제된 회원은 조회되지 않아 404 (PROJECT_SETUP 8절)
    // 순서: 권한 → 대상(404) → 경계 조건(422) → 비활성화 → 진행 중인 업무(409, 확인 불가 502·503) → 삭제
    // 진행 중인 업무가 있거나 확인하지 못하면 비활성화를 되돌린다
    public void deleteUser(UUID requesterId, UserRole headerRole, UUID userId) {
        requesterValidator.verifyMaster(requesterId, headerRole);
        User user = findTarget(userId);

        if (userId.equals(requesterId)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "자기 자신은 삭제할 수 없습니다.");
        }
        if (isLastActiveMaster(user)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "마지막 MASTER는 삭제할 수 없습니다.");
        }

        requireCheckers(ALL_TARGETS, DELETE);
        UserStateTransition.Hold hold = stateTransition.hold(userId);
        checkOrRestore(hold, user, ALL_TARGETS, DELETE);
        stateTransition.delete(hold, requesterId);
    }

    // 확인할 서비스 중 구현체가 없는 것이 있으면 503. 비활성화하기 전에 막는다
    private void requireCheckers(Set<ActiveWorkChecker.Target> targets, String action) {
        Set<ActiveWorkChecker.Target> missing = EnumSet.copyOf(targets);
        activeWorkCheckers.forEach(checker -> missing.remove(checker.target()));
        if (!missing.isEmpty()) {
            log.error("진행 중 업무 확인 구현체 없음. 확인하지 못하는 서비스={}", missing);
            throw new BusinessException(SERVICE_UNAVAILABLE, "진행 중인 업무를 확인할 수 없어 " + action + " 수 없습니다.");
        }
    }

    // 진행 중인 업무가 있으면 409, 확인하지 못하면 502. 어느 쪽이든 hold의 비활성화를 되돌린 뒤 던진다
    // 되돌리기마저 실패하면 회원은 비활성으로 남는다 → 로그를 남기고 그 예외(500)를 던진다. MASTER가 다시 활성화하면 된다
    private void checkOrRestore(UserStateTransition.Hold hold, User user, Set<ActiveWorkChecker.Target> targets,
                                String action) {
        try {
            validateNoActiveWork(user, targets, action);
        } catch (RuntimeException e) {
            try {
                stateTransition.restore(hold);
            } catch (RuntimeException restoreFailure) {
                log.error("회원 활성 상태 되돌리기 실패 — 비활성으로 남음, MASTER가 다시 활성화해야 함. "
                        + "userId={}, 이 요청이 비활성화함={}, 막힌 이유={}",
                        hold.userId(), hold.deactivated(), e.getMessage(), restoreFailure);
                throw restoreFailure;
            }
            throw e;
        }
    }

    // 확인과 변경 사이의 틈은 hold(먼저 비활성화)로 줄이지만 완전히 막지는 못한다 (분산 트랜잭션 없음)
    // - 상대 서비스가 회원 확인을 마치고 저장하기 전이면 놓칠 수 있다
    // - order는 주문 생성 시 /internal/users로 주문자를 확인해 탈퇴(404)·비활성(active=false) 회원을 403으로 거절한다.
    //   hold 뒤에 들어온 주문은 막히지만, hold 전에 회원 확인을 마친 주문은 확인 뒤에 저장될 수 있다
    private void validateNoActiveWork(User user, Set<ActiveWorkChecker.Target> targets, String action) {
        List<ApiErrorDetail> activeWork = new ArrayList<>();
        for (ActiveWorkChecker checker : activeWorkCheckers) {
            if (!targets.contains(checker.target())) {
                continue;
            }
            try {
                checker.findActiveWork(user)
                        .forEach(work -> activeWork.add(new ApiErrorDetail(work.type(), work.reason())));
            } catch (BusinessException e) {
                throw e;
            } catch (RuntimeException e) {
                // 상대 서비스 오류·타임아웃·4xx 모두 "확인하지 못함"으로 보고 막는다
                log.error("회원 업무 관계 확인 실패. target={}, userId={}", checker.target(), user.getId(), e);
                throw new BusinessException(DEPENDENCY_FAILURE, "진행 중인 업무를 확인하지 못해 " + action + " 수 없습니다.");
            }
        }
        if (!activeWork.isEmpty()) {
            throw new BusinessException(UserErrorCode.USER_HAS_ACTIVE_WORK,
                    "진행 중인 업무가 있어 " + action + " 수 없습니다.", activeWork);
        }
    }

    private User findTarget(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND, "존재하지 않거나 탈퇴한 회원입니다.", "userId"));
    }

    // 대상이 활성 MASTER이고, 활성 MASTER가 그 한 명뿐인 경우
    private boolean isLastActiveMaster(User user) {
        return user.getRole() == UserRole.MASTER && user.isActive()
                && userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER) <= 1;
    }

    // 보낸 값이 있고 현재 값과 다를 때만 그 값, 아니면 null (확인 대상 아님)
    private UUID changedOrNull(UUID requested, UUID current) {
        return requested != null && !requested.equals(current) ? requested : null;
    }

    // 대상 본인을 뺀 다른 회원과 아이디·이메일이 겹치면 409 (본인 프로필 수정과 같은 규칙)
    private void validateNotDuplicated(UUID userId, AdminUpdateUserRequest request) {
        List<ApiErrorDetail> details = new ArrayList<>();
        if (request.username() != null && userRepository.existsByUsernameAndIdNot(request.username(), userId)) {
            details.add(new ApiErrorDetail("username", "이미 사용 중인 아이디입니다."));
        }
        if (request.email() != null && userRepository.existsByEmailAndIdNot(request.email(), userId)) {
            details.add(new ApiErrorDetail("email", "이미 사용 중인 이메일입니다."));
        }
        if (!details.isEmpty()) {
            throw new BusinessException(DUPLICATE_RESOURCE, DUPLICATE_MESSAGE, details);
        }
    }
}
