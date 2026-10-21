package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

// 재고 API 권한 규칙만 확인한다. 서비스가 어느 시점에 정책을 부르는지는 InventoryQueryServiceTest·InventoryCommandServiceTest가 확인한다
@ExtendWith(MockitoExtension.class)
class InventoryAccessPolicyTest {

    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Mock
    private CompanyReader companyReader;

    @InjectMocks
    private InventoryAccessPolicy accessPolicy;

    @Nested
    class MASTER_전용 {

        @Test
        void MASTER면_통과한다() {
            assertThatCode(() -> accessPolicy.checkMaster(UserRole.MASTER)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"USER", "COMPANY", "DELIVERY"})
        void MASTER가_아니면_403(UserRole role) {
            assertThatThrownBy(() -> accessPolicy.checkMaster(role))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }
    }

    @Nested
    class 상품별_재고_조회_역할 {

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"MASTER", "COMPANY"})
        void MASTER와_COMPANY는_통과한다(UserRole role) {
            assertThatCode(() -> accessPolicy.checkProductReadable(role)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"USER", "DELIVERY"})
        void 그_외_역할은_403(UserRole role) {
            assertThatThrownBy(() -> accessPolicy.checkProductReadable(role))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }
    }

    @Nested
    class 상품별_재고_업체_소유권 {

        @Test
        void MASTER는_업체를_조회하지_않고_통과한다() {
            assertThatCode(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.MASTER)).doesNotThrowAnyException();

            verifyNoInteractions(companyReader);
        }

        @Test
        void COMPANY가_업체_관리자_본인이면_통과한다() {
            given(companyReader.findOwnerId(COMPANY_ID)).willReturn(Optional.of(USER_ID));

            assertThatCode(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY)).doesNotThrowAnyException();
        }

        @Test
        void COMPANY가_다른_업체_관리자면_403() {
            given(companyReader.findOwnerId(COMPANY_ID)).willReturn(Optional.of(OTHER_USER_ID));

            assertThatThrownBy(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        @Test
        void 업체가_없으면_소유권을_확인할_수_없어_403() {
            given(companyReader.findOwnerId(COMPANY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        // company 오류를 어떻게 넘기는지(404만 empty, 그 외 전파)는 CompanyFeignAdapterTest가 확인한다
        @Test
        void 업체_조회가_실패하면_그_예외를_그대로_던진다() {
            RuntimeException failure = new RuntimeException("company 호출 실패");
            given(companyReader.findOwnerId(COMPANY_ID)).willThrow(failure);

            assertThatThrownBy(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY))
                    .isSameAs(failure);
        }
    }
}
