package com.yuksabal.hub.integration;

import com.yuksabal.hub.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// schema.sql의 제약·인덱스가 실제 PostgreSQL에 만들어져 동작하는지 확인한다 (H2로는 확인할 수 없는 부분)
// 서비스 검증을 거치지 않고 SQL로 직접 넣어, 서비스 코드가 놓쳐도 DB가 막는지 본다
class DatabaseConstraintIntegrationTest extends IntegrationTestSupport {

    // data.sql 경로: 서울(01) → 부산(04)
    private static final UUID SEOUL_TO_BUSAN = UUID.fromString("00000000-0000-0000-0001-000000000004");

    @Test
    void 초기_데이터로_허브_17개와_경로_272개가_들어간다() {
        assertThat(queryForObject("SELECT count(*) FROM hub_service.p_hubs", Integer.class)).isEqualTo(17);
        assertThat(queryForObject("SELECT count(*) FROM hub_service.p_hub_routes", Integer.class)).isEqualTo(272);
    }

    @Nested
    class 재고 {

        @Test
        void 수량은_음수가_될_수_없다() {
            UUID inventoryId = insertInventory(HUB_ID, UUID.randomUUID(), 10);

            assertThatThrownBy(() -> update("UPDATE hub_service.p_inventories SET quantity = -1 WHERE inventory_id = ?", inventoryId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_inventories_quantity_non_negative");
        }

        @Test
        void 한_허브에_같은_상품의_활성_재고는_하나다() {
            UUID productId = UUID.randomUUID();
            insertInventory(HUB_ID, productId, 10);

            assertThatThrownBy(() -> insertInventory(HUB_ID, productId, 5))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_inventories_hub_id_product_id");
        }

        @Test
        void 삭제된_재고가_있어도_같은_허브_상품의_재고를_다시_만들_수_있다() {
            UUID productId = UUID.randomUUID();
            UUID deletedId = insertInventory(HUB_ID, productId, 10);
            update("UPDATE hub_service.p_inventories SET deleted_at = now() WHERE inventory_id = ?", deletedId);

            insertInventory(HUB_ID, productId, 5);

            assertThat(queryForObject("SELECT count(*) FROM hub_service.p_inventories WHERE product_id = ?",
                    Integer.class, productId)).isEqualTo(2);
        }

        // 재고는 허브를 ID로만 참조해 Hibernate가 외래 키를 만들지 않으므로 schema.sql의 외래 키가 막는다 (#268)
        @Test
        void 없는_허브의_재고는_만들_수_없다() {
            assertThatThrownBy(() -> insertInventory(UUID.randomUUID(), UUID.randomUUID(), 10))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_inventories_hub_id");
        }

        @Test
        void 다른_허브에는_같은_상품의_재고를_만들_수_있다() {
            UUID productId = UUID.randomUUID();
            insertInventory(HUB_ID, productId, 10);

            insertInventory(OTHER_HUB_ID, productId, 0);

            assertThat(queryForObject("SELECT count(*) FROM hub_service.p_inventories WHERE product_id = ?",
                    Integer.class, productId)).isEqualTo(2);
        }
    }

    @Nested
    class 재고_로그 {

        @Test
        void 같은_주문의_같은_변경은_한_번만_기록된다() {
            UUID inventoryId = insertInventory(HUB_ID, UUID.randomUUID(), 10);
            UUID orderId = UUID.randomUUID();
            insertLog(inventoryId, orderId, "DECREASE");

            assertThatThrownBy(() -> insertLog(inventoryId, orderId, "DECREASE"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_inventory_logs_order_id_change_type");
        }

        @Test
        void 같은_주문의_차감과_복원은_함께_기록된다() {
            UUID inventoryId = insertInventory(HUB_ID, UUID.randomUUID(), 10);
            UUID orderId = UUID.randomUUID();
            insertLog(inventoryId, orderId, "DECREASE");

            insertLog(inventoryId, orderId, "RESTORE");

            assertThat(countLogs(inventoryId)).isEqualTo(2);
        }

        @Test
        void 주문_ID가_없는_입고는_여러_번_기록된다() {
            UUID inventoryId = insertInventory(HUB_ID, UUID.randomUUID(), 10);
            insertLog(inventoryId, null, "INBOUND");

            insertLog(inventoryId, null, "INBOUND");

            assertThat(countLogs(inventoryId)).isEqualTo(2);
        }

        @Test
        void 정의되지_않은_변경_유형은_저장할_수_없다() {
            UUID inventoryId = insertInventory(HUB_ID, UUID.randomUUID(), 10);

            assertThatThrownBy(() -> insertLog(inventoryId, null, "UNKNOWN"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("p_inventory_logs_change_type_check");
        }
    }

    @Nested
    class 허브_경로 {

        @Test
        void 출발_도착_허브_쌍의_활성_경로는_하나다() {
            assertThatThrownBy(() -> insertRoute(HUB_ID, OTHER_HUB_ID))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_hub_routes_departure_hub_id_arrival_hub_id");
        }

        // 경로도 허브를 ID로만 참조하므로 schema.sql의 외래 키가 막는다 (#268)
        @Test
        void 출발_허브가_없는_경로는_만들_수_없다() {
            assertThatThrownBy(() -> insertRoute(UUID.randomUUID(), OTHER_HUB_ID))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_hub_routes_departure_hub_id");
        }

        @Test
        void 도착_허브가_없는_경로는_만들_수_없다() {
            assertThatThrownBy(() -> insertRoute(HUB_ID, UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_hub_routes_arrival_hub_id");
        }

        @Test
        void 삭제된_경로가_있으면_같은_허브_쌍의_경로를_다시_만들_수_있다() {
            update("UPDATE hub_service.p_hub_routes SET deleted_at = now() WHERE hub_route_id = ?", SEOUL_TO_BUSAN);
            UUID routeId = null;
            try {
                routeId = insertRoute(HUB_ID, OTHER_HUB_ID);

                assertThat(queryForObject(
                        "SELECT count(*) FROM hub_service.p_hub_routes WHERE departure_hub_id = ? AND arrival_hub_id = ?",
                        Integer.class, HUB_ID, OTHER_HUB_ID)).isEqualTo(2);
            } finally {
                // 공통 정리는 재고 테이블만 비우므로, 경로는 여기서 data.sql 상태로 되돌린다
                update("DELETE FROM hub_service.p_hub_routes WHERE hub_route_id = ?", routeId);
                update("UPDATE hub_service.p_hub_routes SET deleted_at = null WHERE hub_route_id = ?", SEOUL_TO_BUSAN);
            }
        }
    }

    private UUID insertInventory(UUID hubId, UUID productId, int quantity) {
        UUID inventoryId = UUID.randomUUID();
        update("INSERT INTO hub_service.p_inventories (inventory_id, hub_id, product_id, company_id, quantity, created_at) "
                + "VALUES (?, ?, ?, ?, ?, now())", inventoryId, hubId, productId, UUID.randomUUID(), quantity);
        return inventoryId;
    }

    private void insertLog(UUID inventoryId, UUID orderId, String changeType) {
        update("INSERT INTO hub_service.p_inventory_logs (inventory_log_id, inventory_id, order_id, change_type, quantity, created_at) "
                + "VALUES (?, ?, ?, ?, 1, now())", UUID.randomUUID(), inventoryId, orderId, changeType);
    }

    private UUID insertRoute(UUID departureHubId, UUID arrivalHubId) {
        UUID routeId = UUID.randomUUID();
        update("INSERT INTO hub_service.p_hub_routes (hub_route_id, departure_hub_id, arrival_hub_id, distance_km, duration_minutes, created_at) "
                + "VALUES (?, ?, ?, 10.00, 10, now())", routeId, departureHubId, arrivalHubId);
        return routeId;
    }

    private int countLogs(UUID inventoryId) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventory_logs WHERE inventory_id = ?", Integer.class, inventoryId);
    }
}
