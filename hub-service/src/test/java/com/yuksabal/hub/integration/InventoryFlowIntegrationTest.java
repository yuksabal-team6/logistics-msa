package com.yuksabal.hub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.hub.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 재고 데이터 흐름: 입고 → 조회 → 수량 조정 → 변경 로그가 실제 DB에 맞게 남는지 확인한다
class InventoryFlowIntegrationTest extends IntegrationTestSupport {

    @Nested
    class 입고 {

        @Test
        void 처음_입고하면_재고가_생기고_INBOUND_로그가_남는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID companyId = UUID.randomUUID();
            givenProduct(productId, companyId, HUB_ID, true);

            JsonNode data = data(asMaster(postJson("/api/v1/inventories/inbound", inboundBody(HUB_ID, productId, 10)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hubId").value(HUB_ID.toString()))
                    .andExpect(jsonPath("$.data.quantity").value(10)));
            UUID inventoryId = UUID.fromString(data.get("inventoryId").asText());

            // 상품의 업체가 함께 저장된다 (COMPANY 소유권 확인용)
            assertThat(queryForObject("SELECT company_id FROM hub_service.p_inventories WHERE inventory_id = ?",
                    UUID.class, inventoryId)).isEqualTo(companyId);
            assertThat(queryForObject(
                    "SELECT change_type || ':' || quantity || ':' || created_by FROM hub_service.p_inventory_logs WHERE inventory_id = ?",
                    String.class, inventoryId)).isEqualTo("INBOUND:10:" + MASTER_ID);
        }

        @Test
        void 재고가_있으면_수량이_늘고_입고마다_상품을_다시_확인한다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);

            asMaster(postJson("/api/v1/inventories/inbound", inboundBody(HUB_ID, productId, 5)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.inventoryId").value(inventoryId.toString()))
                    .andExpect(jsonPath("$.data.quantity").value(15));

            assertThat(countInventories(productId)).isEqualTo(1);
            assertThat(countLogs(inventoryId)).isEqualTo(2);
            assertThat(queryForObject("SELECT updated_by FROM hub_service.p_inventories WHERE inventory_id = ?",
                    UUID.class, inventoryId)).isEqualTo(MASTER_ID);
            verify(companyClient, times(2)).getProduct(productId);
        }

        @Test
        void 비활성_상품이면_422이고_재고가_생기지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            givenProduct(productId, UUID.randomUUID(), HUB_ID, false);

            asMaster(postJson("/api/v1/inventories/inbound", inboundBody(HUB_ID, productId, 10)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                    .andExpect(jsonPath("$.error.details[0].field").value("productId"));

            assertThat(countInventories(productId)).isZero();
        }

        @Test
        void 상품_업체의_관리_허브가_아니면_422이고_details에_관리_허브가_담긴다() throws Exception {
            UUID productId = UUID.randomUUID();
            givenProduct(productId, UUID.randomUUID(), HUB_ID, true);

            asMaster(postJson("/api/v1/inventories/inbound", inboundBody(OTHER_HUB_ID, productId, 10)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.details[0].field").value("hubId"))
                    .andExpect(jsonPath("$.error.details[0].reason").value("상품 업체의 관리 허브는 " + HUB_ID + "입니다."));

            assertThat(countInventories(productId)).isZero();
        }

        @Test
        void 없는_허브면_404이고_company를_호출하지_않는다() throws Exception {
            asMaster(postJson("/api/v1/inventories/inbound", inboundBody(UUID.randomUUID(), UUID.randomUUID(), 10)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.details[0].field").value("hubId"));

            verify(companyClient, never()).getProduct(any());
        }

        @Test
        void company가_상품_404로_응답하면_404이고_field는_상품_ID() throws Exception {
            UUID productId = UUID.randomUUID();
            given(companyClient.getProduct(productId))
                    .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않는 상품입니다.", List.of()));

            asMaster(postJson("/api/v1/inventories/inbound", inboundBody(HUB_ID, productId, 10)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.details[0].field").value("productId"));
        }
    }

    @Nested
    class 조회 {

        @Test
        void 목록은_허브와_상품으로_걸러진다() throws Exception {
            UUID productId = UUID.randomUUID();
            inbound(productId, 10);
            inbound(UUID.randomUUID(), 20);

            asMaster(get("/api/v1/inventories").param("productId", productId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.items[0].productId").value(productId.toString()));
            asMaster(get("/api/v1/inventories").param("hubId", OTHER_HUB_ID.toString()))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        void 목록을_수량순으로_정렬한다() throws Exception {
            inbound(UUID.randomUUID(), 10);
            inbound(UUID.randomUUID(), 30);
            inbound(UUID.randomUUID(), 20);

            asMaster(get("/api/v1/inventories").param("sort", "quantity,desc"))
                    .andExpect(jsonPath("$.data.items[*].quantity").value(contains(30, 20, 10)));
        }

        @Test
        void 상세_조회() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);

            asMaster(get("/api/v1/inventories/" + inventoryId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                    .andExpect(jsonPath("$.data.quantity").value(10));
        }

        @Test
        void 삭제된_재고는_404() throws Exception {
            UUID inventoryId = inbound(UUID.randomUUID(), 10);
            update("UPDATE hub_service.p_inventories SET deleted_at = now() WHERE inventory_id = ?", inventoryId);

            asMaster(get("/api/v1/inventories/" + inventoryId))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 허브별_조회는_재고가_없으면_빈_목록이고_없는_허브면_404() throws Exception {
            inbound(UUID.randomUUID(), 10);

            asMaster(get("/api/v1/inventories/hubs/" + HUB_ID))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
            asMaster(get("/api/v1/inventories/hubs/" + OTHER_HUB_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
            asMaster(get("/api/v1/inventories/hubs/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.details[0].field").value("hubId"));
        }
    }

    @Nested
    class 상품별_조회 {

        @Test
        void 상품_업체의_관리자는_조회할_수_있다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID companyId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            inbound(productId, companyId, 10);
            givenCompany(companyId, ownerId);

            perform(get("/api/v1/inventories/products/" + productId), ownerId, "COMPANY")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.quantity").value(10));
        }

        @Test
        void 다른_업체의_관리자면_403() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID companyId = UUID.randomUUID();
            inbound(productId, companyId, 10);
            givenCompany(companyId, UUID.randomUUID());

            perform(get("/api/v1/inventories/products/" + productId), UUID.randomUUID(), "COMPANY")
                    .andExpect(status().isForbidden());
        }

        @Test
        void MASTER는_company를_호출하지_않고_조회한다() throws Exception {
            UUID productId = UUID.randomUUID();
            inbound(productId, 10);

            asMaster(get("/api/v1/inventories/products/" + productId))
                    .andExpect(status().isOk());
            verify(companyClient, never()).getCompany(any());
        }

        @Test
        void 관리_허브가_바뀌어_이전_허브에_수량_0_재고가_남아도_현재_재고로_응답한다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID companyId = UUID.randomUUID();
            UUID oldInventoryId = inbound(productId, companyId, 10);
            asMaster(patchJson("/api/v1/inventories/" + oldInventoryId, Map.of("expectedQuantity", 10, "quantity", 0)))
                    .andExpect(status().isOk());
            // 업체 관리 허브가 OTHER_HUB_ID로 바뀐 뒤 입고
            givenProduct(productId, companyId, OTHER_HUB_ID, true);
            asMaster(postJson("/api/v1/inventories/inbound", inboundBody(OTHER_HUB_ID, productId, 7)))
                    .andExpect(status().isOk());

            asMaster(get("/api/v1/inventories/products/" + productId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hubId").value(OTHER_HUB_ID.toString()))
                    .andExpect(jsonPath("$.data.quantity").value(7));
        }

        @Test
        void 두_허브_모두_수량이_남아_있으면_409() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID companyId = UUID.randomUUID();
            inbound(productId, companyId, 10);
            givenProduct(productId, companyId, OTHER_HUB_ID, true);
            asMaster(postJson("/api/v1/inventories/inbound", inboundBody(OTHER_HUB_ID, productId, 7)))
                    .andExpect(status().isOk());

            asMaster(get("/api/v1/inventories/products/" + productId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVENTORY_CONFLICT"));
        }

        @Test
        void 재고가_없는_상품이면_404() throws Exception {
            asMaster(get("/api/v1/inventories/products/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.details[0].field").value("productId"));
        }
    }

    @Nested
    class 수량_조정 {

        @Test
        void 줄이면_ADJUST_DECREASE_로그에_차이만큼_남는다() throws Exception {
            UUID inventoryId = inbound(UUID.randomUUID(), 10);

            asMaster(patchJson("/api/v1/inventories/" + inventoryId, Map.of("expectedQuantity", 10, "quantity", 4)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.quantity").value(4));

            assertThat(currentQuantity(inventoryId)).isEqualTo(4);
            assertThat(queryForObject(
                    "SELECT quantity FROM hub_service.p_inventory_logs WHERE inventory_id = ? AND change_type = 'ADJUST_DECREASE'",
                    Integer.class, inventoryId)).isEqualTo(6);
        }

        @Test
        void 늘리면_ADJUST_INCREASE_로그가_남는다() throws Exception {
            UUID inventoryId = inbound(UUID.randomUUID(), 10);

            asMaster(patchJson("/api/v1/inventories/" + inventoryId, Map.of("expectedQuantity", 10, "quantity", 13)))
                    .andExpect(status().isOk());

            assertThat(queryForObject(
                    "SELECT quantity FROM hub_service.p_inventory_logs WHERE inventory_id = ? AND change_type = 'ADJUST_INCREASE'",
                    Integer.class, inventoryId)).isEqualTo(3);
        }

        @Test
        void 조회한_뒤_주문_차감으로_수량이_바뀌었으면_409이고_현재_수량이_담기며_바뀌지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            decrease(productId, 3, UUID.randomUUID());

            asMaster(patchJson("/api/v1/inventories/" + inventoryId, Map.of("expectedQuantity", 10, "quantity", 5)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVENTORY_QUANTITY_CONFLICT"))
                    .andExpect(jsonPath("$.error.details[0].reason").value("현재 재고는 7개입니다."));

            assertThat(currentQuantity(inventoryId)).isEqualTo(7);
            assertThat(countLogs(inventoryId)).isEqualTo(2);
        }

        @Test
        void 조회한_수량과_같은_수량으로_조정하면_400() throws Exception {
            UUID inventoryId = inbound(UUID.randomUUID(), 10);

            asMaster(patchJson("/api/v1/inventories/" + inventoryId, Map.of("expectedQuantity", 10, "quantity", 10)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("quantity"));
        }

        @Test
        void 없는_재고면_404() throws Exception {
            asMaster(patchJson("/api/v1/inventories/" + UUID.randomUUID(), Map.of("expectedQuantity", 10, "quantity", 5)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class 변경_로그 {

        @Test
        void 입고_차감_조정_순서의_로그가_최신순으로_조회된다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            decrease(productId, 3, orderId);
            asMaster(patchJson("/api/v1/inventories/" + inventoryId, Map.of("expectedQuantity", 7, "quantity", 8)))
                    .andExpect(status().isOk());

            asMaster(get("/api/v1/inventories/logs").param("inventoryId", inventoryId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].changeType")
                            .value(contains("ADJUST_INCREASE", "DECREASE", "INBOUND")))
                    .andExpect(jsonPath("$.data.items[1].orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.data.items[1].hubId").value(HUB_ID.toString()))
                    .andExpect(jsonPath("$.data.items[1].productId").value(productId.toString()));
        }

        @Test
        void 조건을_함께_쓰면_AND로_걸러진다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            inbound(productId, 10);
            decrease(productId, 3, orderId);
            inbound(UUID.randomUUID(), 5);

            asMaster(get("/api/v1/inventories/logs")
                            .param("hubId", HUB_ID.toString())
                            .param("changeType", "INBOUND"))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
            asMaster(get("/api/v1/inventories/logs")
                            .param("orderId", orderId.toString())
                            .param("changeType", "INBOUND"))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
            asMaster(get("/api/v1/inventories/logs").param("orderId", orderId.toString()))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    private Map<String, Object> inboundBody(UUID hubId, UUID productId, int quantity) {
        return Map.of("hubId", hubId, "productId", productId, "quantity", quantity);
    }

    // 업체를 정해 HUB_ID에 입고한다 (상품별 조회의 소유권 확인용)
    private UUID inbound(UUID productId, UUID companyId, int quantity) throws Exception {
        givenProduct(productId, companyId, HUB_ID, true);
        JsonNode data = data(asMaster(postJson("/api/v1/inventories/inbound", inboundBody(HUB_ID, productId, quantity)))
                .andExpect(status().isOk()));
        return UUID.fromString(data.get("inventoryId").asText());
    }

    private void decrease(UUID productId, int quantity, UUID orderId) throws Exception {
        mockMvc.perform(postJson("/internal/stocks/decrease",
                        Map.of("hubId", HUB_ID, "productId", productId, "quantity", quantity, "orderId", orderId)))
                .andExpect(status().isOk());
    }

    private int currentQuantity(UUID inventoryId) {
        return queryForObject("SELECT quantity FROM hub_service.p_inventories WHERE inventory_id = ?", Integer.class, inventoryId);
    }

    private int countInventories(UUID productId) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventories WHERE product_id = ?", Integer.class, productId);
    }

    private int countLogs(UUID inventoryId) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventory_logs WHERE inventory_id = ?", Integer.class, inventoryId);
    }
}
