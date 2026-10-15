package com.yuksabal.company.global.auth;

import com.yuksabal.common.exception.BusinessException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

// 업체 관리 권한 판단. 업체 생성·수정과 상품 생성·수정·삭제가 같은 규칙을 쓴다
// 게이트웨이 역할 검사와 별개로 서비스에서도 확인한다
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OwnerPermission {

    // MASTER 또는 업체 관리자 본인(ownerId == userId)만 허용
    public static void validate(UUID ownerId, UUID userId, UserRole role) {
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
