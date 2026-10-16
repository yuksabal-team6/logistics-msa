package com.yuksabal.company.global.auth;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

// 업체 관리 권한 판단. 업체 생성·수정과 상품 생성·수정·삭제가 같은 규칙을 쓴다
// 게이트웨이는 역할만 알기 때문에, 요청자가 "이 업체"의 관리자인지(소유권)는 서비스에서만 확인할 수 있다
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OwnerPermission {

    // MASTER는 모든 업체, COMPANY는 자기가 관리하는 업체(ownerId == userId)만 허용. 그 외 역할은 403
    public static void validateOwner(UUID ownerId, UUID userId, UserRole role) {
        switch (role) {
            case MASTER -> {
                // 모든 업체를 관리할 수 있다
            }
            case COMPANY -> {
                if (!ownerId.equals(userId)) {
                    throw new BusinessException(COMPANY_ACCESS_DENIED);
                }
            }
            default -> throw new BusinessException(FORBIDDEN);
        }
    }
}
