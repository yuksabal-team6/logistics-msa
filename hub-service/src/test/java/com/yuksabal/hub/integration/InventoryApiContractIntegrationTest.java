package com.yuksabal.hub.integration;

import com.yuksabal.hub.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 재고 API 요청·응답 계약: 헤더, 요청 검증, 목록 공통 규칙, 실패 응답 형식
// 서비스 로직 분기는 단위 테스트(InventoryServiceTest·StockServiceTest)에서 확인한다
class InventoryApiContractIntegrationTest extends IntegrationTestSupport {

    @Nested
    class 헤더 {

        @Test
        void 역할_헤더가_없으면_400이고_field에_헤더_이름이_담긴다() throws Exception {
            perform(get("/api/v1/inventories"), MASTER_ID, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("X-User-Role"));
        }

        @Test
        void 알_수_없는_역할이면_403() throws Exception {
            perform(get("/api/v1/inventories"), MASTER_ID, "ADMIN")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }

        @Test
        void MASTER가_아니면_목록_조회는_403() throws Exception {
            perform(get("/api/v1/inventories"), UUID.randomUUID(), "COMPANY")
                    .andExpect(status().isForbidden());
        }

        @Test
        void 입고는_회원_ID_헤더가_없으면_400() throws Exception {
            Map<String, Object> body = Map.of("hubId", HUB_ID, "productId", UUID.randomUUID(), "quantity", 10);

            perform(postJson("/api/v1/inventories/inbound", body), null, "MASTER")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("X-User-Id"));
        }

        @Test
        void 회원_ID_헤더가_UUID가_아니면_400() throws Exception {
            mockMvc.perform(postJson("/api/v1/inventories/inbound",
                                    Map.of("hubId", HUB_ID, "productId", UUID.randomUUID(), "quantity", 10))
                            .header("X-User-Id", "not-a-uuid")
                            .header("X-User-Role", "MASTER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        void 주문_차감은_회원_ID_헤더가_없어도_된다() throws Exception {
            UUID productId = UUID.randomUUID();
            inbound(productId, 10);
            Map<String, Object> body = Map.of("hubId", HUB_ID, "productId", productId, "quantity", 1, "orderId", UUID.randomUUID());

            mockMvc.perform(postJson("/internal/stocks/decrease", body))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class 요청_검증 {

        @Test
        void 입고_필수값이_빠지면_400이고_빠진_필드가_모두_담기고_company를_호출하지_않는다() throws Exception {
            asMaster(postJson("/api/v1/inventories/inbound", Map.of()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[*].field",
                            containsInAnyOrder("hubId", "productId", "quantity")));

            verify(companyClient, never()).getProduct(any());
        }

        @Test
        void 입고_수량이_0이면_400() throws Exception {
            Map<String, Object> body = Map.of("hubId", HUB_ID, "productId", UUID.randomUUID(), "quantity", 0);

            asMaster(postJson("/api/v1/inventories/inbound", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("quantity"));
        }

        @Test
        void 수량_조정에서_음수면_400() throws Exception {
            UUID inventoryId = inbound(UUID.randomUUID(), 10);

            asMaster(patchJson("/api/v1/inventories/" + inventoryId, Map.of("expectedQuantity", 10, "quantity", -1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("quantity"));
        }

        @Test
        void 주문_차감_필수값이_빠지면_400() throws Exception {
            mockMvc.perform(postJson("/internal/stocks/decrease", Map.of()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[*].field",
                            containsInAnyOrder("hubId", "productId", "quantity", "orderId")));
        }

        @Test
        void JSON이_깨져_있으면_400() throws Exception {
            asMaster(post("/api/v1/inventories/inbound").contentType(MediaType.APPLICATION_JSON).content("{\"hubId\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        void 경로의_재고_ID가_UUID가_아니면_400이고_field에_파라미터_이름이_담긴다() throws Exception {
            asMaster(get("/api/v1/inventories/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("inventoryId"));
        }

        @Test
        void 변경_유형이_정의되지_않은_값이면_400() throws Exception {
            asMaster(get("/api/v1/inventories/logs").param("changeType", "UNKNOWN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        void 재고_확인은_상품과_업체를_함께_보내면_400() throws Exception {
            mockMvc.perform(get("/internal/inventories/stock")
                            .param("productId", UUID.randomUUID().toString())
                            .param("companyId", UUID.randomUUID().toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("singleTarget"));
        }

        @Test
        void 재고_확인은_상품과_업체가_모두_없으면_400() throws Exception {
            mockMvc.perform(get("/internal/inventories/stock"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("singleTarget"));
        }
    }

    @Nested
    class 목록_공통 {

        @Test
        void 페이지_응답_형식() throws Exception {
            inbound(UUID.randomUUID(), 10);

            asMaster(get("/api/v1/inventories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1));
        }

        @Test
        void 허용하지_않은_정렬_필드면_400() throws Exception {
            asMaster(get("/api/v1/inventories").param("sort", "productId,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        }

        @Test
        void 로그는_생성일_외에_정렬할_수_없다() throws Exception {
            asMaster(get("/api/v1/inventories/logs").param("sort", "quantity,desc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        }

        @Test
        void 페이지_크기는_최대_100으로_줄어든다() throws Exception {
            asMaster(get("/api/v1/inventories").param("size", "1000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(100));
        }
    }

    @Nested
    class 실패_응답 {

        @Test
        void 없는_재고면_404이고_field에_재고_ID가_담긴다() throws Exception {
            asMaster(get("/api/v1/inventories/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.error.details[0].field").value("inventoryId"));
        }

        @Test
        void 없는_URL이면_404() throws Exception {
            asMaster(get("/api/v1/unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        void 지원하지_않는_메서드면_405() throws Exception {
            asMaster(post("/api/v1/inventories/logs"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        }
    }
}
