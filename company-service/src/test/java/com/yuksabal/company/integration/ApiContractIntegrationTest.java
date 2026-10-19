package com.yuksabal.company.integration;

import com.yuksabal.company.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청·응답 계약: 헤더, 요청 검증, 목록 공통 규칙, 응답·감사 필드, 내부 API
// 서비스 로직 분기는 단위 테스트(CompanyServiceTest·ProductServiceTest)에서 확인한다
class ApiContractIntegrationTest extends IntegrationTestSupport {

    @Nested
    class 헤더 {

        @Test
        void 역할_헤더가_없으면_400이고_field에_헤더_이름이_담긴다() throws Exception {
            perform(postJson("/api/v1/companies", companyBody(owner(null))), MASTER_ID, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("X-User-Role"));
        }

        @Test
        void 알_수_없는_역할이면_403() throws Exception {
            perform(postJson("/api/v1/companies", companyBody(owner(null))), MASTER_ID, "ADMIN")
                    .andExpect(status().isForbidden());
        }

        @Test
        void 회원_ID_헤더가_UUID가_아니면_400() throws Exception {
            mockMvc.perform(postJson("/api/v1/companies", companyBody(owner(null)))
                            .header("X-User-Id", "not-a-uuid")
                            .header("X-User-Role", "MASTER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        void 조회는_사용자_헤더_없이도_된다() throws Exception {
            UUID companyId = createCompany(owner(null));

            mockMvc.perform(getUrl("/api/v1/companies/" + companyId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class 요청_검증 {

        @Test
        void 필수값이_빠지면_400이고_빠진_필드가_모두_담긴다() throws Exception {
            asMaster(postJson("/api/v1/companies", Map.of()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[*].field",
                            containsInAnyOrder("companyName", "hubId", "ownerId", "address")));
        }

        @Test
        void 사업자_등록번호_형식이_틀리면_400() throws Exception {
            Map<String, Object> body = companyBody(owner(null));
            body.put("businessNumber", "1234567890");

            asMaster(postJson("/api/v1/companies", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("businessNumber"));
        }

        @Test
        void JSON이_깨져_있으면_400() throws Exception {
            asMaster(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content("{\"companyName\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        void 경로의_ID가_UUID가_아니면_400() throws Exception {
            mockMvc.perform(getUrl("/api/v1/companies/123"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("companyId"));
        }

        @Test
        void 수정에서_업체명을_공백만_보내면_400() throws Exception {
            UUID companyId = createCompany(owner(null));

            asMaster(patchJson("/api/v1/companies/" + companyId, Map.of("companyName", "   ")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("companyName"));
        }

        @Test
        void 상품_단가가_음수면_400() throws Exception {
            UUID companyId = createCompany(owner(null));

            asMaster(postJson("/api/v1/companies/" + companyId + "/products",
                    Map.of("productName", "고등어", "unitPrice", -1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("unitPrice"));
        }

        @Test
        void 상태_변경에서_active를_빼면_400() throws Exception {
            UUID companyId = createCompany(owner(null));

            asMaster(patchJson("/api/v1/companies/" + companyId + "/status", Map.of()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("active"));
        }
    }

    @Nested
    class 목록 {

        @Test
        void 정렬을_지정하지_않으면_최신순() throws Exception {
            createCompany(companyBody(owner(null), "먼저 만든 업체"));
            createCompany(companyBody(owner(null), "나중에 만든 업체"));

            mockMvc.perform(getUrl("/api/v1/companies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].companyName").value("나중에 만든 업체"))
                    .andExpect(jsonPath("$.data.items[1].companyName").value("먼저 만든 업체"));
        }

        @Test
        void 페이지_크기는_최대_100() throws Exception {
            mockMvc.perform(getUrl("/api/v1/companies?size=500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(100));
        }

        @Test
        void 허용하지_않은_필드로_정렬하면_400() throws Exception {
            mockMvc.perform(getUrl("/api/v1/companies?sort=ownerId,desc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        }

        @Test
        void 업체명_검색의_퍼센트는_문자_그대로_찾는다() throws Exception {
            createCompany(companyBody(owner(null), "만족도 100% 수산"));
            createCompany(companyBody(owner(null), "100년 수산"));

            mockMvc.perform(getUrl("/api/v1/companies").param("companyName", "100%"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.items[0].companyName").value("만족도 100% 수산"));
        }

        @Test
        void 활성_여부로_거를_수_있다() throws Exception {
            UUID active = createCompany(companyBody(owner(null), "활성 업체"));
            UUID inactive = createCompany(companyBody(owner(null), "비활성 업체"));
            changeCompanyStatus(inactive, false);

            mockMvc.perform(getUrl("/api/v1/companies?active=false"))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.items[0].companyId").value(inactive.toString()));
            mockMvc.perform(getUrl("/api/v1/companies?active=true"))
                    .andExpect(jsonPath("$.data.items[*].companyId", hasItem(active.toString())))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        void 상품은_소속_업체의_허브로_거를_수_있다() throws Exception {
            UUID companyId = createCompany(owner(null));
            createProduct(companyId, "고등어");

            mockMvc.perform(getUrl("/api/v1/products?hubId=" + HUB_ID))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
            mockMvc.perform(getUrl("/api/v1/products?hubId=" + OTHER_HUB_ID))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    @Nested
    class 응답과_감사_필드 {

        @Test
        void 생성_응답은_201이고_활성_상태와_생성_시각이_담긴다() throws Exception {
            asMaster(postJson("/api/v1/companies", companyBody(owner(null))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("업체가 생성되었습니다."))
                    .andExpect(jsonPath("$.data.active").value(true))
                    .andExpect(jsonPath("$.data.createdAt").exists());
        }

        @Test
        void 생성자와_수정자는_요청_헤더의_회원_ID로_기록된다() throws Exception {
            UUID ownerId = owner(null);
            UUID companyId = createCompany(ownerId);
            givenOwner(ownerId, companyId);

            asCompany(ownerId, patchJson("/api/v1/companies/" + companyId, Map.of("contactPhone", "051-000-0000")))
                    .andExpect(status().isOk());

            assertThat(queryForObject("SELECT created_by FROM company_service.p_companies WHERE company_id = ?",
                    UUID.class, companyId)).isEqualTo(MASTER_ID);
            assertThat(queryForObject("SELECT updated_by FROM company_service.p_companies WHERE company_id = ?",
                    UUID.class, companyId)).isEqualTo(ownerId);
        }

        @Test
        void 실패_응답은_success_false와_error만_담긴다() throws Exception {
            mockMvc.perform(getUrl("/api/v1/companies/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    class 내부_API {

        @Test
        void 업체_조회_2번은_허브와_관리자와_활성_여부를_준다() throws Exception {
            UUID ownerId = owner(null);
            UUID companyId = createCompany(ownerId);

            mockMvc.perform(getUrl("/internal/companies/" + companyId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.data.hubId").value(HUB_ID.toString()))
                    .andExpect(jsonPath("$.data.ownerId").value(ownerId.toString()))
                    .andExpect(jsonPath("$.data.active").value(true));
        }

        @Test
        void 관리자로_조회_11번은_ownerId가_없으면_400() throws Exception {
            mockMvc.perform(getUrl("/internal/companies"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("ownerId"));
        }

        @Test
        void 관리자로_조회_11번은_관리_업체가_없으면_빈_목록() throws Exception {
            mockMvc.perform(getUrl("/internal/companies?ownerId=" + UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void 상품_조회_1번은_소속_업체와_단가와_활성_여부를_준다() throws Exception {
            UUID companyId = createCompany(owner(null));
            UUID productId = createProduct(companyId, "고등어");

            mockMvc.perform(getUrl("/internal/products/" + productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                    .andExpect(jsonPath("$.data.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.data.unitPrice").value(5000))
                    .andExpect(jsonPath("$.data.active").value(true));
        }
    }

    private Map<String, Object> companyBody(UUID ownerId, String companyName) {
        Map<String, Object> body = companyBody(ownerId);
        body.put("companyName", companyName);
        return body;
    }
}
