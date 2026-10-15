package com.yuksabal.delivery.global.auth;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;

public enum UserRole {
    MASTER,
    USER,
    DELIVERY,
    COMPANY;

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
