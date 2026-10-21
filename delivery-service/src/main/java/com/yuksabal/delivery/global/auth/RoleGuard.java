package com.yuksabal.delivery.global.auth;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;

public final class RoleGuard {

    private RoleGuard() {
    }

    public static void requireMaster(UserRole role, String message) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, message);
        }
    }
}
