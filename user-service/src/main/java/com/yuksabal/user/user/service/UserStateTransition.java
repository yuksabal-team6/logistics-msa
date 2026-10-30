package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.user.user.dto.request.AdminUpdateUserRequest;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;

// 회원 관리의 DB 쓰기 단계. 메서드마다 트랜잭션 하나 (UserAdminService는 Feign 호출을 트랜잭션 밖에서 한다)
//
// 진행 중 업무 확인이 필요한 변경(삭제·비활성화·DELIVERY 역할 변경, #68)은 세 단계로 나눈다 (delivery 담당자 제안)
// 1) hold: 계정을 먼저 비활성화해 커밋한다. 다른 서비스는 비활성 회원을 새 담당자로 지정하지 않으므로(delivery·company 422)
//    확인과 변경 사이에 새 업무가 생기는 틈이 줄어든다
// 2) 확인: UserAdminService가 Feign으로 진행 중 업무를 조회한다
// 3) 확정(delete·changeRole) 또는 되돌리기(restore). 대상 행을 잠그고, 1) 뒤에 다른 변경이 없었을 때만 한다 (updated_at 비교)
// 3) 전에 서버가 멈추면 회원은 비활성으로 남는다 (역할·소속은 그대로라 안전한 쪽). MASTER가 다시 활성화하면 된다
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStateTransition {

    private static final String CHANGED_MESSAGE = "처리하는 동안 회원 정보가 변경되었습니다. 다시 시도해 주세요.";

    private final UserRepository userRepository;
    private final EntityManager entityManager;

    // 1) 결과. deactivated = 이 요청이 비활성화했는지 (원래 비활성이었으면 되돌릴 것이 없다)
    // updatedAt = 1) 직후 DB에 저장된 값. 3)에서 다른 변경이 끼어들었는지 비교한다
    public record Hold(UUID userId, boolean deactivated, LocalDateTime updatedAt) {
    }

    @Transactional
    public Hold hold(UUID userId) {
        protectLastMaster(userId);
        User user = findTarget(userId);
        if (!user.isActive()) {
            return new Hold(userId, false, user.getUpdatedAt());
        }
        user.changeActive(false);
        userRepository.flush();
        // 비교 기준을 DB에 저장된 값(정밀도 포함)으로 맞추려고 다시 읽는다
        entityManager.refresh(user);
        return new Hold(userId, true, user.getUpdatedAt());
    }

    // 3) 확인 통과: 소프트 삭제. 1)에서 비활성화한 상태는 그대로 둔다 (삭제된 회원은 조회되지 않는다)
    @Transactional
    public void delete(Hold hold, UUID deletedBy) {
        lockUnchanged(hold).delete(deletedBy);
    }

    // 3) 확인 통과: 역할 변경 + 1)에서 비활성화했으면 다시 활성화
    @Transactional
    public User changeRole(Hold hold, UserRole role) {
        User user = lockUnchanged(hold);
        user.changeRole(role);
        if (hold.deactivated()) {
            user.changeActive(true);
        }
        return user;
    }

    // 3) 진행 중 업무가 있거나 확인 실패: 1)에서 비활성화한 것을 되돌린다.
    // 그사이 다른 MASTER가 회원을 바꿨으면 그 변경을 덮어쓰지 않고 비활성으로 둔다
    @Transactional
    public void restore(Hold hold) {
        if (!hold.deactivated()) {
            return;
        }
        Optional<User> user = userRepository.findByIdForUpdate(hold.userId());
        if (user.isEmpty() || !Objects.equals(user.get().getUpdatedAt(), hold.updatedAt())) {
            log.warn("회원 활성 상태를 되돌리지 않음: 확인 중 다른 변경이 있었음. userId={}", hold.userId());
            return;
        }
        user.get().changeActive(true);
    }

    // MASTER 회원 정보 수정. 확인(중복·소속·업체 관리자)은 UserAdminService가 트랜잭션 밖에서 끝냈다.
    // 확인할 때 읽은 updated_at과 다르면 그사이 다른 변경이 있었던 것이므로 저장하지 않고 422
    // 동시 수정으로 중복 확인을 함께 통과하면 부분 유니크 인덱스(#15)가 막고 common 핸들러가 409로 응답한다
    @Transactional
    public User update(UUID userId, LocalDateTime checkedUpdatedAt, AdminUpdateUserRequest request) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(UserStateTransition::notFound);
        if (!Objects.equals(user.getUpdatedAt(), checkedUpdatedAt)) {
            log.warn("회원 정보 수정 중단: 확인 중 다른 변경이 있었음. userId={}", userId);
            throw new BusinessException(BUSINESS_RULE_VIOLATION, CHANGED_MESSAGE);
        }
        user.updateProfile(request.username(), request.email(), request.slackId(), request.isPublic());
        user.changeAffiliation(request.hubId(), request.companyId());
        userRepository.flush();
        return user;
    }

    // 진행 중 업무 확인이 필요 없는 변경
    @Transactional
    public User changeRole(UUID userId, UserRole role) {
        if (role != UserRole.MASTER) {
            protectLastMaster(userId);
        }
        User user = findTarget(userId);
        user.changeRole(role);
        return user;
    }

    @Transactional
    public User changeActive(UUID userId, boolean active) {
        User user = findTarget(userId);
        user.changeActive(active);
        return user;
    }

    private User lockUnchanged(Hold hold) {
        User user = userRepository.findByIdForUpdate(hold.userId()).orElseThrow(UserStateTransition::notFound);
        if (!Objects.equals(user.getUpdatedAt(), hold.updatedAt())) {
            log.warn("회원 변경 중단: 확인 중 다른 변경이 있었음. userId={}, 이 요청이 비활성화함={}",
                    hold.userId(), hold.deactivated());
            throw new BusinessException(BUSINESS_RULE_VIOLATION, CHANGED_MESSAGE);
        }
        return user;
    }

    // 대상이 마지막 활성 MASTER면 422. UserAdminService의 확인은 잠금 밖이라 서로를 동시에 비활성화·강등하면 함께 통과한다
    // 활성 MASTER 전체를 잠근 뒤 세므로 그런 요청은 차례로 처리된다. 대상 행보다 먼저 잠가야 교착이 생기지 않는다
    private void protectLastMaster(UUID userId) {
        List<User> activeMasters = userRepository.findActiveByRoleForUpdate(UserRole.MASTER);
        if (activeMasters.size() <= 1 && activeMasters.stream().anyMatch(master -> master.getId().equals(userId))) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "마지막 활성 MASTER는 변경할 수 없습니다.");
        }
    }

    // 행을 잠그고 읽는다. User는 모든 컬럼을 다시 쓰므로, 잠그지 않으면 그사이 커밋된 본인 수정(updateMe·비밀번호)을 덮어쓴다
    private User findTarget(UUID userId) {
        return userRepository.findByIdForUpdate(userId).orElseThrow(UserStateTransition::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(RESOURCE_NOT_FOUND, "존재하지 않거나 탈퇴한 회원입니다.", "userId");
    }
}
