package com.yuksabal.company.support;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BusinessErrorAssertions {

    // 검사만: 대부분의 테스트
    @SuppressWarnings("ThrowableNotThrown")
    public static void assertBusinessError(ThrowingCallable call, ErrorCode expected) {
        catchBusinessError(call, expected);
    }

    // 검사 + 꺼내기: 메시지나 details까지 볼 때
    public static BusinessException catchBusinessError(ThrowingCallable call, ErrorCode expected) {
        BusinessException e = catchThrowableOfType(call, BusinessException.class);
        assertThat(e).as("BusinessException이 나야 한다").isNotNull();
        assertThat(e.getErrorCode()).isEqualTo(expected);
        return e;
    }
}
