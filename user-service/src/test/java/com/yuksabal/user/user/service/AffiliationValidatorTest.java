package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.HubClient;
import org.junit.jupiter.api.Test;

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

// USE-15 의존성 장애: 회원가입·MASTER 회원 수정의 허브·업체 존재 확인 (HubClient·CompanyClient Mock)
// 없는 것(404)과 확인하지 못한 것(5xx·타임아웃)을 구분한다. 확인하지 못했으면 잡지 않아 common이 502로 응답한다
class AffiliationValidatorTest {

    private final HubClient hubClient = mock(HubClient.class);
    private final CompanyClient companyClient = mock(CompanyClient.class);
    private final AffiliationValidator validator = new AffiliationValidator(hubClient, companyClient);

    private final UUID hubId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @Test
    void 허브와_업체가_모두_있으면_통과한다() {
        // when & then
        assertThatCode(() -> validator.validate(hubId, companyId)).doesNotThrowAnyException();
        verify(hubClient).getHub(hubId);
        verify(companyClient).getCompany(companyId);
    }

    @Test
    void 값이_없으면_조회하지_않는다() {
        // when
        validator.validate(null, null);

        // then
        verify(hubClient, never()).getHub(any());
        verify(companyClient, never()).getCompany(any());
    }

    @Test
    void 허브와_업체가_모두_없으면_400이고_둘_다_알려준다() {
        // given
        given(hubClient.getHub(hubId)).willThrow(clientError(404));
        given(companyClient.getCompany(companyId)).willThrow(clientError(404));

        // when & then
        assertThatThrownBy(() -> validator.validate(hubId, companyId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(e.getDetails()).extracting(ApiErrorDetail::field)
                            .containsExactly("hubId", "companyId");
                });
    }

    @Test
    void 허브_서비스_장애는_없는_것으로_바꾸지_않고_그대로_던진다() {
        // given
        FeignServerException outage = new FeignServerException(503, "SERVICE_UNAVAILABLE", "down", "HubClient#getHub");
        given(hubClient.getHub(hubId)).willThrow(outage);

        // when & then
        // 그대로 올라가 common FeignExceptionHandler가 502로 응답한다. 업체 확인까지 가지 않는다
        assertThatThrownBy(() -> validator.validate(hubId, companyId)).isSameAs(outage);
        verify(companyClient, never()).getCompany(any());
    }

    @Test
    void 업체_서비스가_404가_아닌_4xx를_주면_그대로_던진다() {
        // given
        FeignClientException forbidden = clientError(403);
        given(companyClient.getCompany(companyId)).willThrow(forbidden);

        // when & then
        assertThatThrownBy(() -> validator.validate(null, companyId)).isSameAs(forbidden);
    }

    private static FeignClientException clientError(int status) {
        return new FeignClientException(status, "ERROR", "error", List.of());
    }
}
