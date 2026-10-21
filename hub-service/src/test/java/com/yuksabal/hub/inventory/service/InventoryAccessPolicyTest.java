package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.inventory.client.CompanyClient;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

// 재고 API 권한 규칙만 확인한다. 서비스가 어느 시점에 정책을 부르는지는 InventoryServiceTest가 확인한다
@ExtendWith(MockitoExtension.class)
class InventoryAccessPolicyTest {

    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Mock
    private CompanyClient companyClient;

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
        void MASTER는_company를_호출하지_않고_통과한다() {
            assertThatCode(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.MASTER)).doesNotThrowAnyException();

            verifyNoInteractions(companyClient);
        }

        @Test
        void COMPANY가_업체_관리자_본인이면_통과한다() {
            given(companyClient.getCompany(COMPANY_ID)).willReturn(ApiResponse.success(new CompanyResponse(COMPANY_ID, USER_ID)));

            assertThatCode(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY)).doesNotThrowAnyException();
        }

        @Test
        void COMPANY가_다른_업체_관리자면_403() {
            given(companyClient.getCompany(COMPANY_ID)).willReturn(ApiResponse.success(new CompanyResponse(COMPANY_ID, OTHER_USER_ID)));

            assertThatThrownBy(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        @Test
        void 업체가_없으면_소유권을_확인할_수_없어_403() {
            given(companyClient.getCompany(COMPANY_ID))
                    .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", List.of()));

            assertThatThrownBy(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        @Test
        void 업체_조회가_404가_아닌_이유로_실패하면_그_예외를_그대로_던진다() {
            FeignClientException badRequest = new FeignClientException(400, "VALIDATION_ERROR", "잘못된 요청입니다.", List.of());
            given(companyClient.getCompany(COMPANY_ID)).willThrow(badRequest);

            assertThatThrownBy(() -> accessPolicy.checkProductOwner(COMPANY_ID, USER_ID, UserRole.COMPANY))
                    .isSameAs(badRequest);
        }
    }
}
