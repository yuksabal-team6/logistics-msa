package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.user.dto.request.AdminUpdateUserRequest;
import com.yuksabal.user.user.dto.request.ChangeRoleRequest;
import com.yuksabal.user.user.dto.request.ChangeStatusRequest;
import com.yuksabal.user.user.dto.response.UserResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// MASTER용 회원 관리 (#67): 정보 수정, 역할 변경, 계정 활성화·비활성화
// - 요청자는 활성 MASTER여야 한다 (헤더 역할과 DB 역할 대조, RequesterValidator)
// - 대상이 없거나 탈퇴(소프트 삭제)했으면 404. 비활성 회원은 대상이 될 수 있다 (다시 활성화하려면 필요)
// - 자기 자신의 역할 변경·비활성화, 마지막 활성 MASTER의 강등·비활성화는 422
// - 이미 같은 역할·상태면 변경 없이 200 (같은 요청을 다시 보내도 결과가 같다)
@Service
@RequiredArgsConstructor
@Transactional
public class UserAdminService {

    private static final String DUPLICATE_MESSAGE = "이미 사용 중인 아이디 또는 이메일입니다.";

    private final UserRepository userRepository;
    private final RequesterValidator requesterValidator;
    private final AffiliationValidator affiliationValidator;
    private final CompanyOwnerGuard companyOwnerGuard;

    // 허브·업체는 값이 바뀔 때만 내부 API로 존재를 확인한다 (현재 값과 같으면 호출하지 않음)
    // 업체 관리자(COMPANY)의 companyId는 관리 업체로만 바꿀 수 있다 (422, CompanyOwnerGuard)
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

        user.updateProfile(request.username(), request.email(), request.slackId(), request.isPublic());
        user.changeAffiliation(request.hubId(), request.companyId());
        // 동시 수정으로 중복 확인을 함께 통과하면 부분 유니크 인덱스(#15)가 막고 common 핸들러가 409로 응답한다
        userRepository.flush();
        return UserResponse.from(user);
    }

    // 역할을 바꿔도 대상이 이미 받은 토큰에는 예전 역할이 남아 있다 (토큰 무효화 없음).
    // user-service는 헤더 역할과 DB 역할이 다르면 403이라, 대상은 다시 로그인해야 한다
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
        user.changeRole(newRole);
        return UserResponse.from(user);
    }

    // 비활성화해도 대상이 이미 받은 토큰은 만료 전까지 유효하다. user-service API는 DB를 확인해 403으로 막지만,
    // 헤더만 신뢰하는 다른 서비스는 만료(최대 1시간)까지 막지 못한다
    public UserResponse changeStatus(UUID requesterId, UserRole headerRole, UUID userId, ChangeStatusRequest request) {
        requesterValidator.verifyMaster(requesterId, headerRole);
        User user = findTarget(userId);
        boolean active = request.active();

        if (user.isActive() == active) {
            return UserResponse.from(user);
        }
        if (!active && userId.equals(requesterId)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "자기 자신의 계정은 비활성화할 수 없습니다.");
        }
        if (!active && isLastActiveMaster(user)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, "마지막 MASTER 계정은 비활성화할 수 없습니다.");
        }
        user.changeActive(active);
        return UserResponse.from(user);
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
