package com.yuksabal.hub.inventory.infrastructure;

import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.inventory.infrastructure.dto.CompanyResponse;
import com.yuksabal.hub.inventory.infrastructure.dto.ProductResponse;
import com.yuksabal.hub.inventory.application.ProductInfo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// company-service 응답을 재고 서비스가 쓰는 형태로 바꾸는지, 404만 "없음"으로 바꾸고 나머지 오류는 그대로 넘기는지 확인한다
@ExtendWith(MockitoExtension.class)
class CompanyFeignAdapterTest {

    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Mock
    private CompanyClient companyClient;

    @InjectMocks
    private CompanyFeignAdapter adapter;

    @Nested
    class 상품_조회 {

        @Test
        void company_응답을_ProductInfo로_바꾼다() {
            given(companyClient.getProduct(PRODUCT_ID))
                    .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, HUB_ID, true)));

            assertThat(adapter.findProduct(PRODUCT_ID)).contains(new ProductInfo(PRODUCT_ID, COMPANY_ID, HUB_ID, true));
        }

        @Test
        void company가_404면_비어_있다() {
            given(companyClient.getProduct(PRODUCT_ID)).willThrow(notFound());

            assertThat(adapter.findProduct(PRODUCT_ID)).isEmpty();
        }

        @Test
        void company가_404가_아닌_4xx면_그대로_던진다() {
            FeignClientException badRequest = badRequest();
            given(companyClient.getProduct(PRODUCT_ID)).willThrow(badRequest);

            assertThatThrownBy(() -> adapter.findProduct(PRODUCT_ID)).isSameAs(badRequest);
        }

        @Test
        void company가_5xx면_그대로_던진다() {
            FeignServerException serverError = new FeignServerException(503, "SERVICE_UNAVAILABLE", "서비스를 사용할 수 없습니다.", "CompanyClient#getProduct");
            given(companyClient.getProduct(PRODUCT_ID)).willThrow(serverError);

            assertThatThrownBy(() -> adapter.findProduct(PRODUCT_ID)).isSameAs(serverError);
        }
    }

    @Nested
    class 업체_관리자_조회 {

        @Test
        void 업체_관리자_ID를_돌려준다() {
            given(companyClient.getCompany(COMPANY_ID)).willReturn(ApiResponse.success(new CompanyResponse(COMPANY_ID, OWNER_ID)));

            assertThat(adapter.findOwnerId(COMPANY_ID)).contains(OWNER_ID);
        }

        @Test
        void company가_404면_비어_있다() {
            given(companyClient.getCompany(COMPANY_ID)).willThrow(notFound());

            assertThat(adapter.findOwnerId(COMPANY_ID)).isEmpty();
        }

        @Test
        void company가_404가_아닌_4xx면_그대로_던진다() {
            FeignClientException badRequest = badRequest();
            given(companyClient.getCompany(COMPANY_ID)).willThrow(badRequest);

            assertThatThrownBy(() -> adapter.findOwnerId(COMPANY_ID)).isSameAs(badRequest);
        }
    }

    private static FeignClientException notFound() {
        return new FeignClientException(404, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", List.of());
    }

    private static FeignClientException badRequest() {
        return new FeignClientException(400, "VALIDATION_ERROR", "잘못된 요청입니다.", List.of());
    }
}
