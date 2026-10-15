package com.yuksabal.user.user.entity;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;

// role_type. MASTER = 관리자, DELIVERY = 배송 담당자, COMPANY = 업체 관리자
// 회원가입은 항상 USER, 나머지 역할은 MASTER만 부여한다. api-gateway UserRole과 같은 값을 유지한다.
public enum UserRole {
    MASTER, USER, DELIVERY, COMPANY;

    // 게이트웨이가 넣어 준 X-User-Role 값 변환. 없거나 모르는 값이면 403 (company-service UserRole.from과 같은 규칙)
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
