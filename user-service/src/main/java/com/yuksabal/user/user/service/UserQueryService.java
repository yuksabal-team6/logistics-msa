package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.user.user.dto.request.UserSearchRequest;
import com.yuksabal.user.user.dto.response.UserResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.entity.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.user.user.repository.UserSpecification.hubIdEquals;
import static com.yuksabal.user.user.repository.UserSpecification.roleEquals;
import static com.yuksabal.user.user.repository.UserSpecification.usernameContains;

// MASTER용 회원 조회. 역할은 게이트웨이가 검증한 X-User-Role이지만 서비스에서도 다시 확인한다 (CLAUDE.md 5.5)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "username");

    private final UserRepository userRepository;
    private final Validator validator;

    // 권한(403)을 입력값 검사(400)보다 먼저 본다: MASTER가 아니면 검색 조건과 무관하게 403
    public PageResponse<UserResponse> getUsers(UUID requesterId, UserRole headerRole, UserSearchRequest request,
                                               Pageable pageable) {
        User requester = userRepository.findById(requesterId).orElseThrow(() -> new BusinessException(FORBIDDEN));
        validateRoleMatches(requester, headerRole);
        if (requester.getRole() != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN);
        }
        validate(request);

        Specification<User> spec = Specification
                .where(usernameContains(request.username()))
                .and(roleEquals(request.role() == null ? null : UserRole.valueOf(request.role())))
                .and(hubIdEquals(request.hubId() == null ? null : UUID.fromString(request.hubId())));

        Page<User> users = userRepository.findAll(spec, toStablePageable(pageable));
        return PageResponse.from(users, UserResponse::from);
    }

    // MASTER는 누구나, 그 외 역할은 본인만. 다른 회원 조회는 대상 존재 여부와 무관하게 403 (회원 ID 존재 노출 방지)
    // 요청자는 X-User-Id의 활성 회원이어야 한다 (임의의 UUID는 요청자로 인정하지 않는다)
    public UserResponse getUser(UUID requesterId, UserRole headerRole, UUID userId) {
        boolean self = userId.equals(requesterId);
        User requester = userRepository.findById(requesterId).orElse(null);

        if (requester == null) {
            // 본인 조회면 대상(=본인)이 없거나 탈퇴한 것이므로 404, 다른 회원 조회면 403
            if (self) {
                throw notFound();
            }
            throw new BusinessException(FORBIDDEN);
        }
        validateRoleMatches(requester, headerRole);
        if (self) {
            return UserResponse.from(requester);
        }
        if (requester.getRole() != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN);
        }
        return userRepository.findById(userId).map(UserResponse::from).orElseThrow(this::notFound);
    }

    // 헤더 역할(게이트웨이가 토큰에서 넣은 값)이 DB에 저장된 요청자의 역할과 다르면 403.
    // 본인 조회라도 역할을 위조한 요청(예: USER가 MASTER로 보냄)이나 토큰 발급 뒤 역할이 바뀐 요청은 거부한다
    private void validateRoleMatches(User requester, UserRole headerRole) {
        if (requester.getRole() != headerRole) {
            throw new BusinessException(FORBIDDEN);
        }
    }

    private BusinessException notFound() {
        return new BusinessException(RESOURCE_NOT_FOUND, "존재하지 않거나 탈퇴한 회원입니다.", "userId");
    }

    private void validate(UserSearchRequest request) {
        List<ApiErrorDetail> details = validator.validate(request).stream()
                .map(violation -> new ApiErrorDetail(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(ApiErrorDetail::field))
                .toList();
        if (!details.isEmpty()) {
            throw new BusinessException(VALIDATION_ERROR, VALIDATION_ERROR.getMessage(), details);
        }
    }

    // 허용하지 않은 정렬 필드는 400. 정렬 값이 같은 행의 순서가 페이지마다 바뀌지 않게 id를 마지막 기준으로 붙인다
    // (company-service·hub-service toStablePageable과 같은 규칙)
    private Pageable toStablePageable(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new BusinessException(VALIDATION_ERROR, "정렬할 수 없는 필드입니다: " + order.getProperty(), "sort");
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(Sort.by("id")));
    }
}
