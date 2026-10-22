package com.yuksabal.company.global.auth;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

// 업체·상품 API 권한. 권한이 없으면 403 (역할 FORBIDDEN, 남의 업체 COMPANY_ACCESS_DENIED)
// 게이트웨이는 역할만 알기 때문에, 요청자가 "이 업체"의 관리자인지(소유권)는 서비스에서만 확인할 수 있다
// 판단에 필요한 값(역할, 요청자, 업체의 ownerId)이 모두 서비스에 있어 외부 조회가 없으므로 정적 메서드로 둔다
// 권한 판단에 다른 서비스 조회가 필요해지면 hub InventoryAccessPolicy처럼 빈으로 바꾼다
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CompanyAccessPolicy {

    // MASTER는 모든 업체, COMPANY는 자기가 관리하는 업체(ownerId == userId)만 허용. 그 외 역할은 403
    public static void checkOwner(UUID ownerId, UUID userId, UserRole role) {
        switch (role) {
            case MASTER -> {
                // 모든 업체를 관리할 수 있다
            }
            case COMPANY -> {
                // 소유자를 확인할 수 없으면(ownerId·userId null) 거절한다. 지금은 둘 다 null이 될 수 없지만 NPE(500) 대신 403이 되도록 막는다
                if (ownerId == null || !ownerId.equals(userId)) {
                    throw new BusinessException(COMPANY_ACCESS_DENIED);
                }
            }
            default -> throw new BusinessException(FORBIDDEN);
        }
    }

    // 업체 상태 변경, 업체 삭제는 MASTER만 허용. 그 외 역할은 403
    public static void checkMaster(UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN);
        }
    }

    // 관리 허브·관리자 변경은 MASTER만 허용. 바뀌는 것이 없으면 COMPANY도 통과
    public static void checkHubOrOwnerChangeable(boolean hubChanged, boolean ownerChanged, UserRole role) {
        if (hubChanged || ownerChanged) {
            checkMaster(role);
        }
    }
}
