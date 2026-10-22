package com.yuksabal.company.global.auth;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static com.yuksabal.common.auth.UserRole.COMPANY;
import static com.yuksabal.common.auth.UserRole.MASTER;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.company.global.auth.CompanyAccessPolicy.checkOwner;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 정적 메서드라 Mock·스프링 없이 값만 넣어 권한 판단을 확인한다
class CompanyAccessPolicyTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    @Nested
    class 소유권_확인 {

        @Test
        void MASTER는_다른_사람의_업체도_통과한다() {
            assertThatCode(() -> checkOwner(OWNER_ID, OTHER_ID, MASTER))
                    .doesNotThrowAnyException();
        }

        @Test
        void COMPANY는_남의_업체면_COMPANY_ACCESS_DENIED() {
            assertThatThrownBy(() -> checkOwner(OWNER_ID, OTHER_ID, COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(COMPANY_ACCESS_DENIED));
        }

        @Test
        void COMPANY는_본인_업체면_통과한다() {
            UUID ownerId = UUID.fromString("10000000-0000-4000-8000-000000000001");
            UUID userId = UUID.fromString("10000000-0000-4000-8000-000000000001");

            assertThat(ownerId).isEqualTo(userId);
            assertThat(ownerId).isNotSameAs(userId);

            assertThatCode(() -> checkOwner(ownerId, userId, COMPANY))
                    .doesNotThrowAnyException();
        }

        @Test
        void ownerId가_null이면_COMPANY_ACCESS_DENIED() {
            UUID ownerId = null;

            assertThatThrownBy(() -> checkOwner(ownerId, OTHER_ID, COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(COMPANY_ACCESS_DENIED));
        }

        @Test
        void userId가_null이면_COMPANY_ACCESS_DENIED() {
            UUID userId = null;

            assertThatThrownBy(() -> checkOwner(OWNER_ID, userId, COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(COMPANY_ACCESS_DENIED));
        }

        @Test
        void 둘_다_null이면_COMPANY_ACCESS_DENIED() {
            UUID ownerId = null;
            UUID userId = null;

            assertThatThrownBy(() -> checkOwner(ownerId, userId, COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(COMPANY_ACCESS_DENIED));
        }

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"USER", "DELIVERY"})
        void MASTER와_COMPANY가_아니면_FORBIDDEN(UserRole role) {
            assertThatThrownBy(() -> checkOwner(OWNER_ID, OWNER_ID, role))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }
    }
}
