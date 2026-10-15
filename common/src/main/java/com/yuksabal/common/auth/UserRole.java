package com.yuksabal.common.auth;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;

// 회원 역할. user-service는 role 컬럼에 이름(EnumType.STRING)으로 저장하고, 나머지 서비스는 X-User-Role 헤더 값을 from()으로 변환한다
// MASTER = 관리자, USER = 일반 회원, DELIVERY = 배송 담당자, COMPANY = 업체 관리자. api-gateway UserRole과 같은 값을 유지한다
public enum UserRole {
    MASTER,
    USER,
    DELIVERY,
    COMPANY;

    // 헤더가 없거나 정의되지 않은 역할이면 권한 없음(403)
    public static UserRole from(String role) {
        if (role == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
