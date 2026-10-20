package com.yuksabal.hub.integration;

import com.yuksabal.hub.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내부 API: order의 주문 재고 차감·복원, company의 삭제 전 재고 확인
// 같은 주문의 차감·복원이 한 번만 반영되는지(멱등성) 실제 재고 수량과 로그로 확인한다
class StockInternalIntegrationTest extends IntegrationTestSupport {

    @Nested
    class 주문_차감 {

        @Test
        void 차감하면_수량이_줄고_DECREASE_로그에_주문_ID가_남는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();

            decrease(productId, 3, orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remainingQuantity").value(7))
                    .andExpect(jsonPath("$.data.orderId").value(orderId.toString()));

            assertThat(currentQuantity(inventoryId)).isEqualTo(7);
            assertThat(logQuantity(orderId, "DECREASE")).isEqualTo(3);
        }

        @Test
        void 같은_주문으로_다시_차감하면_변경_없이_200() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();
            decrease(productId, 3, orderId);

            decrease(productId, 3, orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remainingQuantity").value(7));

            assertThat(currentQuantity(inventoryId)).isEqualTo(7);
            assertThat(countOrderLogs(orderId)).isEqualTo(1);
        }

        @Test
        void 남은_수량이_부족하면_409이고_현재_수량이_담기며_로그가_남지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 2);
            UUID orderId = UUID.randomUUID();

            decrease(productId, 3, orderId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.error.details[0].reason").value("현재 재고는 2개입니다."));

            assertThat(currentQuantity(inventoryId)).isEqualTo(2);
            assertThat(countOrderLogs(orderId)).isZero();
        }

        @Test
        void 남은_수량과_같은_수량은_차감되어_0이_된다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 3);

            decrease(productId, 3, UUID.randomUUID())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remainingQuantity").value(0));

            assertThat(currentQuantity(inventoryId)).isZero();
        }

        @Test
        void 해당_허브에_상품_재고가_없으면_404() throws Exception {
            decrease(UUID.randomUUID(), 1, UUID.randomUUID())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.details[0].field").value("productId"));
        }

        @Test
        void 이미_복원된_주문의_늦은_차감은_차감하지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();
            // 차감 요청이 타임아웃되어 order가 먼저 복원을 보낸 경우
            restore(productId, 3, orderId)
                    .andExpect(jsonPath("$.data.restoredQuantity").value(0));

            decrease(productId, 3, orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remainingQuantity").value(10));

            assertThat(currentQuantity(inventoryId)).isEqualTo(10);
            assertThat(countOrderLogs(orderId)).isEqualTo(1);
        }
    }

    @Nested
    class 주문_복원 {

        @Test
        void 차감한_수량만큼_되돌리고_RESTORE_로그가_남는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();
            decrease(productId, 3, orderId);

            // 복원 수량은 요청 수량이 아니라 실제로 차감한 수량이다
            restore(productId, 5, orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.restoredQuantity").value(3));

            assertThat(currentQuantity(inventoryId)).isEqualTo(10);
            assertThat(logQuantity(orderId, "RESTORE")).isEqualTo(3);
        }

        @Test
        void 같은_주문으로_다시_복원하면_변경_없이_처음_복원한_수량으로_200() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();
            decrease(productId, 3, orderId);
            restore(productId, 3, orderId);

            restore(productId, 3, orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.restoredQuantity").value(3));

            assertThat(currentQuantity(inventoryId)).isEqualTo(10);
            assertThat(countOrderLogs(orderId)).isEqualTo(2);
        }

        @Test
        void 차감_기록이_없으면_재고는_그대로_두고_수량_0인_RESTORE_로그를_남긴다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();

            restore(productId, 3, orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.restoredQuantity").value(0));

            assertThat(currentQuantity(inventoryId)).isEqualTo(10);
            assertThat(logQuantity(orderId, "RESTORE")).isZero();
        }

        @Test
        void 차감한_재고와_요청의_상품이_다르면_422이고_아무것도_바뀌지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID otherProductId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID otherInventoryId = inbound(otherProductId, 10);
            UUID orderId = UUID.randomUUID();
            decrease(productId, 3, orderId);

            restore(otherProductId, 3, orderId)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.details[1].reason").value("차감한 상품은 " + productId + "입니다."));

            assertThat(currentQuantity(inventoryId)).isEqualTo(7);
            assertThat(currentQuantity(otherInventoryId)).isEqualTo(10);
            assertThat(countOrderLogs(orderId)).isEqualTo(1);
        }
    }

    @Nested
    class 재고_확인 {

        @Test
        void 수량이_남은_재고가_있으면_true() throws Exception {
            UUID productId = UUID.randomUUID();
            inbound(productId, 10);

            mockMvc.perform(get("/internal/inventories/stock").param("productId", productId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasStock").value(true));
        }

        @Test
        void 수량_0인_재고만_있으면_false() throws Exception {
            UUID productId = UUID.randomUUID();
            inbound(productId, 3);
            decrease(productId, 3, UUID.randomUUID());

            mockMvc.perform(get("/internal/inventories/stock").param("productId", productId.toString()))
                    .andExpect(jsonPath("$.data.hasStock").value(false));
        }

        @Test
        void 업체_기준으로_확인한다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID companyId = UUID.randomUUID();
            givenProduct(productId, companyId, HUB_ID, true);
            asMaster(postJson("/api/v1/inventories/inbound", Map.of("hubId", HUB_ID, "productId", productId, "quantity", 10)))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/internal/inventories/stock").param("companyId", companyId.toString()))
                    .andExpect(jsonPath("$.data.hasStock").value(true));
            mockMvc.perform(get("/internal/inventories/stock").param("companyId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasStock").value(false));
        }
    }

    private ResultActions decrease(UUID productId, int quantity, UUID orderId) throws Exception {
        return mockMvc.perform(postJson("/internal/stocks/decrease", stockBody(productId, quantity, orderId)));
    }

    private ResultActions restore(UUID productId, int quantity, UUID orderId) throws Exception {
        return mockMvc.perform(postJson("/internal/stocks/restore", stockBody(productId, quantity, orderId)));
    }

    private Map<String, Object> stockBody(UUID productId, int quantity, UUID orderId) {
        return Map.of("hubId", HUB_ID, "productId", productId, "quantity", quantity, "orderId", orderId);
    }

    private int currentQuantity(UUID inventoryId) {
        return queryForObject("SELECT quantity FROM hub_service.p_inventories WHERE inventory_id = ?", Integer.class, inventoryId);
    }

    private int logQuantity(UUID orderId, String changeType) {
        return queryForObject("SELECT quantity FROM hub_service.p_inventory_logs WHERE order_id = ? AND change_type = ?",
                Integer.class, orderId, changeType);
    }

    private int countOrderLogs(UUID orderId) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventory_logs WHERE order_id = ?", Integer.class, orderId);
    }
}
