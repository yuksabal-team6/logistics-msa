package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.dto.CompanyResponse;
import com.yuksabal.user.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// USE-14 업체 관리자 변경 정합성: 업체의 owner_id가 회원을 가리키면 그 회원의 company_id도 그 업체여야 한다
// owner_id는 company가 관리하므로 user는 관리 업체 조회(#11, CompanyClient Mock)로 확인하고 소속·역할 변경을 막는다
class CompanyOwnerGuardTest {

    private final CompanyClient companyClient = mock(CompanyClient.class);
    private final CompanyOwnerGuard guard = new CompanyOwnerGuard(companyClient);

    private final UUID ownedCompany = UUID.randomUUID();
    private final UUID otherCompany = UUID.randomUUID();
    private final User owner = user(UserRole.COMPANY);

    // ---------- 소속(companyId) 변경 ----------

    @Test
    void 관리하는_업체로_소속을_바꾸는_것은_허용한다() {
        // given
        givenOwned(owner, ownedCompany);

        // when & then
        assertThatCode(() -> guard.validateCompanyChange(owner, ownedCompany)).doesNotThrowAnyException();
    }

    @Test
    void 업체_관리자의_소속을_다른_업체로_바꾸면_422() {
        // given
        givenOwned(owner, ownedCompany);

        // when & then
        assertRuleViolation(() -> guard.validateCompanyChange(owner, otherCompany), "companyId");
    }

    @Test
    void 관리하는_업체가_둘_이상이면_데이터_이상이라_422() {
        // given
        // 한 회원은 한 업체만 관리한다 (company가 409로 막음). 둘 이상이면 어느 쪽으로 맞출지 알 수 없다
        givenOwned(owner, ownedCompany, otherCompany);

        // when & then
        assertRuleViolation(() -> guard.validateCompanyChange(owner, ownedCompany), "companyId");
    }

    @Test
    void 관리하는_업체가_없으면_어느_업체로든_바꿀_수_있다() {
        // given
        givenOwned(owner);

        // when & then
        assertThatCode(() -> guard.validateCompanyChange(owner, otherCompany)).doesNotThrowAnyException();
    }

    @Test
    void 소속이_바뀌지_않거나_COMPANY가_아니면_업체를_조회하지_않는다() {
        // given
        User deliveryUser = user(UserRole.DELIVERY);

        // when
        guard.validateCompanyChange(owner, null);
        guard.validateCompanyChange(deliveryUser, otherCompany);

        // then
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    // ---------- 역할 변경 ----------

    @Test
    void 업체_관리자를_다른_역할로_바꾸면_422() {
        // given
        givenOwned(owner, ownedCompany);

        // when & then
        assertRuleViolation(() -> guard.validateRoleChange(owner, UserRole.USER), "role");
    }

    @Test
    void 관리하는_업체가_없는_COMPANY는_다른_역할로_바꿀_수_있다() {
        // given
        givenOwned(owner);

        // when & then
        assertThatCode(() -> guard.validateRoleChange(owner, UserRole.USER)).doesNotThrowAnyException();
    }

    @Test
    void COMPANY가_아니거나_COMPANY로_그대로면_업체를_조회하지_않는다() {
        // given
        User user = user(UserRole.USER);

        // when
        guard.validateRoleChange(user, UserRole.COMPANY);
        guard.validateRoleChange(owner, UserRole.COMPANY);

        // then
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    // ---------- 회원 삭제 (CompanyActiveWorkChecker) ----------

    @Test
    void 삭제_확인용_관리_업체_ID는_COMPANY만_조회한다() {
        // given
        givenOwned(owner, ownedCompany);
        User user = user(UserRole.USER);

        // when & then
        assertThat(guard.ownedCompanyIds(owner)).containsExactly(ownedCompany);
        assertThat(guard.ownedCompanyIds(user)).isEmpty();
        verify(companyClient, never()).getCompaniesByOwner(user.getId());
    }

    // ---------- 관리 업체 조회 응답 이상 (USE-15) ----------

    @Test
    void 관리_업체_응답이_비어_있으면_확인하지_못한_것으로_보고_502() {
        // given
        given(companyClient.getCompaniesByOwner(owner.getId())).willReturn(null);

        // when & then
        assertDependencyFailure(() -> guard.validateRoleChange(owner, UserRole.USER));
    }

    @Test
    void 관리_업체_목록이_null이면_502() {
        // given
        given(companyClient.getCompaniesByOwner(owner.getId())).willReturn(ApiResponse.success(null));

        // when & then
        assertDependencyFailure(() -> guard.validateCompanyChange(owner, otherCompany));
    }

    @Test
    void 관리_업체_항목의_업체_ID가_비어_있으면_502() {
        // given
        given(companyClient.getCompaniesByOwner(owner.getId()))
                .willReturn(ApiResponse.success(List.of(new CompanyResponse(null))));

        // when & then
        assertDependencyFailure(() -> guard.ownedCompanyIds(owner));
    }

    private void givenOwned(User user, UUID... companyIds) {
        List<CompanyResponse> companies = Arrays.stream(companyIds).map(CompanyResponse::new).toList();
        given(companyClient.getCompaniesByOwner(user.getId())).willReturn(ApiResponse.success(companies));
    }

    private void assertRuleViolation(Runnable call, String field) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
                    assertThat(e.getMessage()).isEqualTo("업체 관리자를 먼저 변경해 주세요.");
                    assertThat(e.getDetails()).extracting(ApiErrorDetail::field).containsExactly(field);
                });
    }

    private void assertDependencyFailure(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.DEPENDENCY_FAILURE);
    }

    private static User user(UserRole role) {
        User user = User.builder().username("user01").password("$2a$hash").email("user01@example.com")
                .slackId("U1").role(role).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
