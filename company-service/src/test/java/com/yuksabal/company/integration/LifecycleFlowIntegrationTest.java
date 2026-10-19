package com.yuksabal.company.integration;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.dto.response.InventoryStockResponse;
import com.yuksabal.company.global.client.dto.response.OrderInProgressResponse;
import com.yuksabal.company.support.IntegrationTestSupport;
import feign.RetryableException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 데이터 흐름: 여러 요청이 이어질 때 업체·상품 상태가 서로 맞게 유지되는지
// 비활성화 → 새 거래 차단 → 재활성화, 삭제 전 확인 → 삭제 → 조회·재사용, 관리자 교체 → 권한 이동
class LifecycleFlowIntegrationTest extends IntegrationTestSupport {

    @Nested
    class 비활성화와_재활성화 {

        @Test
        void 업체를_비활성화하면_상품도_비활성이_되고_새_상품과_활성화가_막힌다() throws Exception {
            UUID companyId = createCompany(owner(null));
            UUID productId = createProduct(companyId, "고등어");

            changeCompanyStatus(companyId, false);

            // 상품도 비활성, 내부 #1도 비활성 (order·hub가 새 거래를 막는 기준)
            mockMvc.perform(getUrl("/api/v1/products/" + productId))
                    .andExpect(jsonPath("$.data.active").value(false));
            mockMvc.perform(getUrl("/internal/products/" + productId))
                    .andExpect(jsonPath("$.data.active").value(false));
            mockMvc.perform(getUrl("/internal/companies/" + companyId))
                    .andExpect(jsonPath("$.data.active").value(false));

            // 새 상품 등록·상품 활성화 422
            asMaster(postJson("/api/v1/companies/" + companyId + "/products",
                    Map.of("productName", "갈치", "unitPrice", 1000)))
                    .andExpect(status().isUnprocessableEntity());
            asMaster(patchJson("/api/v1/products/" + productId + "/status", Map.of("active", true)))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void 업체를_다시_활성화해도_상품은_비활성으로_남고_따로_활성화해야_한다() throws Exception {
            UUID companyId = createCompany(owner(null));
            UUID productId = createProduct(companyId, "고등어");
            changeCompanyStatus(companyId, false);

            changeCompanyStatus(companyId, true);

            mockMvc.perform(getUrl("/internal/products/" + productId))
                    .andExpect(jsonPath("$.data.active").value(false));

            changeProductStatus(productId, true);

            mockMvc.perform(getUrl("/internal/products/" + productId))
                    .andExpect(jsonPath("$.data.active").value(true));
        }

        @Test
        void 비활성_업체도_정보_수정과_조회는_된다() throws Exception {
            UUID companyId = createCompany(owner(null));
            changeCompanyStatus(companyId, false);

            asMaster(patchJson("/api/v1/companies/" + companyId, Map.of("contactPhone", "051-000-0000")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contactPhone").value("051-000-0000"))
                    .andExpect(jsonPath("$.data.active").value(false));
        }
    }

    @Nested
    class 삭제 {

        @Test
        void 업체_삭제는_비활성화_후_남은_업무가_없을_때만_되고_상품도_함께_사라진다() throws Exception {
            UUID companyId = createCompany(owner(null));
            UUID productId = createProduct(companyId, "고등어");

            // 1. 활성 업체 422
            asMaster(deleteUrl("/api/v1/companies/" + companyId))
                    .andExpect(status().isUnprocessableEntity());

            // 2. 비활성화 후 재고·진행 중 주문이 남아 있으면 409 (둘 다 알려준다)
            changeCompanyStatus(companyId, false);
            given(hubClient.getStockByCompany(companyId)).willReturn(ApiResponse.success(new InventoryStockResponse(true)));
            given(orderClient.getInProgressByCompany(companyId)).willReturn(ApiResponse.success(new OrderInProgressResponse(true)));

            asMaster(deleteUrl("/api/v1/companies/" + companyId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("HAS_ACTIVE_WORK"))
                    .andExpect(jsonPath("$.error.details[*].field", containsInAnyOrder("inventory", "order")));

            // 3. 남은 업무가 없어지면 200
            given(hubClient.getStockByCompany(companyId)).willReturn(ApiResponse.success(new InventoryStockResponse(false)));
            given(orderClient.getInProgressByCompany(companyId)).willReturn(ApiResponse.success(new OrderInProgressResponse(false)));

            asMaster(deleteUrl("/api/v1/companies/" + companyId))
                    .andExpect(status().isOk());

            // 4. 업체·상품 모두 외부·내부 조회 404, 목록에서 빠짐. 행은 남고 삭제자가 기록됨 (Soft Delete)
            mockMvc.perform(getUrl("/api/v1/companies/" + companyId)).andExpect(status().isNotFound());
            mockMvc.perform(getUrl("/internal/companies/" + companyId)).andExpect(status().isNotFound());
            mockMvc.perform(getUrl("/api/v1/products/" + productId)).andExpect(status().isNotFound());
            mockMvc.perform(getUrl("/internal/products/" + productId)).andExpect(status().isNotFound());
            mockMvc.perform(getUrl("/api/v1/products?companyId=" + companyId))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
            assertThat(queryForObject("SELECT deleted_by FROM company_service.p_products WHERE product_id = ?",
                    UUID.class, productId)).isEqualTo(MASTER_ID);
        }

        @Test
        void 확인할_서비스가_응답하지_않으면_502이고_삭제되지_않는다() throws Exception {
            UUID companyId = createCompany(owner(null));
            UUID productId = createProduct(companyId, "고등어");
            changeProductStatus(productId, false);
            given(hubClient.getStockByProduct(any())).willThrow(mock(RetryableException.class));

            asMaster(deleteUrl("/api/v1/products/" + productId))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));

            mockMvc.perform(getUrl("/api/v1/products/" + productId)).andExpect(status().isOk());
        }

        @Test
        void 상품만_삭제하면_업체와_다른_상품은_그대로다() throws Exception {
            UUID companyId = createCompany(owner(null));
            UUID deleted = createProduct(companyId, "고등어");
            UUID kept = createProduct(companyId, "갈치");
            changeProductStatus(deleted, false);

            asMaster(deleteUrl("/api/v1/products/" + deleted)).andExpect(status().isOk());

            mockMvc.perform(getUrl("/api/v1/products?companyId=" + companyId))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.items[0].productId").value(kept.toString()));
            mockMvc.perform(getUrl("/api/v1/companies/" + companyId)).andExpect(status().isOk());
        }

        @Test
        void 삭제된_업체의_관리자와_사업자_등록번호는_새_업체에_다시_쓸_수_있다() throws Exception {
            UUID ownerId = owner(null);
            Map<String, Object> body = companyBody(ownerId);
            body.put("businessNumber", "123-45-67890");
            UUID companyId = createCompany(body);

            // 삭제 전에는 같은 관리자·사업자 등록번호로 만들 수 없다
            asMaster(postJson("/api/v1/companies", body)).andExpect(status().isConflict());

            changeCompanyStatus(companyId, false);
            asMaster(deleteUrl("/api/v1/companies/" + companyId)).andExpect(status().isOk());

            asMaster(postJson("/api/v1/companies", body)).andExpect(status().isCreated());
        }
    }

    @Nested
    class 관리자_교체 {

        @Test
        void 관리자를_바꾸면_이전_관리자는_업체와_상품을_관리할_수_없다() throws Exception {
            UUID oldOwner = owner(null);
            UUID companyId = createCompany(oldOwner);
            UUID productId = createProduct(companyId, "고등어");
            UUID newOwner = owner(companyId);   // 새 관리자는 먼저 이 업체 소속이어야 한다

            asMaster(patchJson("/api/v1/companies/" + companyId, Map.of("ownerId", newOwner.toString())))
                    .andExpect(status().isOk());

            asCompany(oldOwner, patchJson("/api/v1/products/" + productId, Map.of("unitPrice", 1)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMPANY_ACCESS_DENIED"));
            asCompany(newOwner, patchJson("/api/v1/products/" + productId, Map.of("unitPrice", 1)))
                    .andExpect(status().isOk());

            // 내부 #11: 이전 관리자는 관리 업체 없음, 새 관리자는 이 업체
            mockMvc.perform(getUrl("/internal/companies?ownerId=" + oldOwner))
                    .andExpect(jsonPath("$.data").isEmpty());
            mockMvc.perform(getUrl("/internal/companies?ownerId=" + newOwner))
                    .andExpect(jsonPath("$.data[0].companyId").value(companyId.toString()));
        }

        @Test
        void 이전_관리자는_새_업체의_관리자가_될_수_있다() throws Exception {
            UUID oldOwner = owner(null);
            UUID companyId = createCompany(oldOwner);
            UUID newOwner = owner(companyId);
            asMaster(patchJson("/api/v1/companies/" + companyId, Map.of("ownerId", newOwner.toString())))
                    .andExpect(status().isOk());

            createCompany(oldOwner);
        }
    }

    @Nested
    class 허브_이전 {

        @Test
        void 업체의_허브를_바꾸면_상품의_허브도_새_허브로_보인다() throws Exception {
            UUID companyId = createCompany(owner(null));
            UUID productId = createProduct(companyId, "고등어");

            asMaster(patchJson("/api/v1/companies/" + companyId, Map.of("hubId", OTHER_HUB_ID.toString())))
                    .andExpect(status().isOk());

            // 상품은 허브를 따로 갖지 않고 업체 허브를 따른다 → order가 #1·#2로 받는 출발 허브도 바뀐다
            mockMvc.perform(getUrl("/internal/products/" + productId))
                    .andExpect(jsonPath("$.data.hubId").value(OTHER_HUB_ID.toString()));
            mockMvc.perform(getUrl("/api/v1/products?hubId=" + OTHER_HUB_ID))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }
}
