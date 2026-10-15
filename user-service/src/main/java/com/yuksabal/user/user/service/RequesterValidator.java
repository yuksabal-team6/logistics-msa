package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;

// 요청자 확인. X-User-Id·X-User-Role은 게이트웨이가 JWT에서 넣지만, 서비스에서도 DB와 대조한다 (CLAUDE.md 5.5)
// - X-User-Id가 삭제되지 않은 회원이어야 한다 (임의의 UUID는 요청자로 인정하지 않는다)
// - 비활성 계정이면 403 ACCOUNT_DISABLED (토큰이 아직 유효해도)
// - 헤더 역할이 DB 역할과 다르면 403 (역할 위조, 토큰 발급 뒤 역할 변경)
@Component
@RequiredArgsConstructor
public class RequesterValidator {

    private final UserRepository userRepository;

    public User verify(UUID requesterId, UserRole headerRole) {
        User requester = userRepository.findById(requesterId).orElseThrow(() -> new BusinessException(FORBIDDEN));
        check(requester, headerRole);
        return requester;
    }

    public User verifyMaster(UUID requesterId, UserRole headerRole) {
        User requester = verify(requesterId, headerRole);
        if (requester.getRole() != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN);
        }
        return requester;
    }

    // 이미 조회한 요청자에 대해 활성 여부와 역할 일치만 확인
    public void check(User requester, UserRole headerRole) {
        if (!requester.isActive()) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        if (requester.getRole() != headerRole) {
            throw new BusinessException(FORBIDDEN);
        }
    }
}
