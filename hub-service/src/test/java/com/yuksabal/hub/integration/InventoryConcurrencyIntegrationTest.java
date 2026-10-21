package com.yuksabal.hub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuksabal.hub.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

// 관리자 재고 조정(CAS)과 입고(원자 UPDATE·유니크 인덱스)에 요청이 동시에 몰려도 재고 수량과 로그가 정확한지 확인한다
// 주문 차감(StockService)과 섞여 와도 서로 덮어쓰지 않는지 함께 본다
// 어느 순서로 처리될지 정할 수 없는 경우는 "재고 수량 = 로그로 계산한 수량"처럼 순서와 관계없이 성립하는 조건으로 확인한다
class InventoryConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final int THREAD_COUNT = 20;

    @Nested
    class 재고_조정 {

        @Test
        void 같은_수량을_보고_동시에_조정하면_한_건만_성공하고_나머지는_409() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);

            // 요청마다 조정할 수량을 다르게 해, 성공한 요청의 수량이 그대로 남았는지 확인한다
            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> adjust(inventoryId, 10, 11 + i));

            assertThat(countStatus(results, 200)).isEqualTo(1);
            assertThat(countStatus(results, 409)).isEqualTo(THREAD_COUNT - 1);
            assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 409)
                    .allSatisfy(result -> assertThat(errorCode(result)).isEqualTo("INVENTORY_QUANTITY_CONFLICT"));
            MvcResult success = results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow();
            assertThat(currentQuantity(inventoryId)).isEqualTo(data(success).get("quantity").asInt());
            assertThat(countInventoryLogs(inventoryId, "ADJUST_INCREASE")).isEqualTo(1);
        }

        @Test
        void 조정과_주문_차감이_동시에_와도_재고_수량이_로그와_일치한다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 100);

            // 0번은 100 → 50 조정, 나머지는 주문 차감. 차감이 먼저 커밋되면 조정은 409
            List<MvcResult> results = runConcurrently(THREAD_COUNT,
                    i -> i == 0 ? adjust(inventoryId, 100, 50) : decrease(productId, 1, UUID.randomUUID()));

            assertThat(results.get(0).getResponse().getStatus()).isIn(200, 409);
            assertThat(results.subList(1, THREAD_COUNT))
                    .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            int decreased = THREAD_COUNT - 1;
            int expected = results.get(0).getResponse().getStatus() == 200 ? 50 - decreased : 100 - decreased;
            assertThat(currentQuantity(inventoryId)).isEqualTo(expected);
            assertThat(currentQuantity(inventoryId)).isEqualTo(quantityFromLogs(inventoryId));
        }
    }

    @Nested
    class 입고 {

        @Test
        void 이미_있는_재고에_동시에_입고해도_입고가_하나도_사라지지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);

            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> inboundRequest(productId, 5));

            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            assertThat(currentQuantity(inventoryId)).isEqualTo(10 + 5 * THREAD_COUNT);
            assertThat(countInventoryLogs(inventoryId, "INBOUND")).isEqualTo(1 + THREAD_COUNT);
        }

        @Test
        void 같은_허브_상품에_동시에_첫_입고하면_재고는_하나만_생기고_실패는_409() throws Exception {
            UUID productId = UUID.randomUUID();
            givenProduct(productId, UUID.randomUUID(), HUB_ID, true);

            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> inboundRequest(productId, 5));

            // 첫 입고가 커밋된 뒤에 재고를 조회한 요청은 409가 아니라 기존 재고에 더해지므로 성공 개수는 정해지지 않는다
            long succeeded = countStatus(results, 200);
            assertThat(succeeded).isPositive();
            assertThat(succeeded + countStatus(results, 409)).isEqualTo(THREAD_COUNT);
            assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 409)
                    .allSatisfy(result -> assertThat(errorCode(result)).isEqualTo("INVENTORY_CREATE_CONFLICT"));
            assertThat(countInventories(productId)).isEqualTo(1);
            UUID inventoryId = inventoryIdOf(productId);
            assertThat(currentQuantity(inventoryId)).isEqualTo(5 * succeeded);
            assertThat(countInventoryLogs(inventoryId, "INBOUND")).isEqualTo(succeeded);
        }

        @Test
        void 입고와_주문_차감이_동시에_와도_서로_덮어쓰지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 100);

            // 짝수는 5개 입고, 홀수는 3개 주문 차감
            List<MvcResult> results = runConcurrently(THREAD_COUNT,
                    i -> i % 2 == 0 ? inboundRequest(productId, 5) : decrease(productId, 3, UUID.randomUUID()));

            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            int half = THREAD_COUNT / 2;
            assertThat(currentQuantity(inventoryId)).isEqualTo(100 + 5 * half - 3 * half);
            assertThat(currentQuantity(inventoryId)).isEqualTo(quantityFromLogs(inventoryId));
        }
    }

    // 요청을 모두 준비시킨 뒤 한꺼번에 출발시키고, 끝난 순서와 상관없이 요청 순서대로 결과를 돌려준다
    private List<MvcResult> runConcurrently(int count, IntFunction<MockHttpServletRequestBuilder> requestFactory)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                MockHttpServletRequestBuilder request = requestFactory.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(request).andReturn();
                }));
            }
            ready.await();
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- 요청 ----

    private MockHttpServletRequestBuilder adjust(UUID inventoryId, int expectedQuantity, int quantity) {
        return master(json(() -> patchJson("/api/v1/inventories/" + inventoryId,
                Map.of("expectedQuantity", expectedQuantity, "quantity", quantity))));
    }

    // 상품 응답(company)은 inbound() 또는 givenProduct()로 미리 지정해 둔다
    private MockHttpServletRequestBuilder inboundRequest(UUID productId, int quantity) {
        return master(json(() -> postJson("/api/v1/inventories/inbound",
                Map.of("hubId", HUB_ID, "productId", productId, "quantity", quantity))));
    }

    private MockHttpServletRequestBuilder decrease(UUID productId, int quantity, UUID orderId) {
        return json(() -> postJson("/internal/stocks/decrease",
                Map.of("hubId", HUB_ID, "productId", productId, "quantity", quantity, "orderId", orderId)));
    }

    private MockHttpServletRequestBuilder master(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", MASTER_ID.toString()).header("X-User-Role", "MASTER");
    }

    private interface RequestSupplier {
        MockHttpServletRequestBuilder get() throws Exception;
    }

    private MockHttpServletRequestBuilder json(RequestSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 응답 ----

    private JsonNode body(MvcResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode data(MvcResult result) {
        return body(result).get("data");
    }

    private String errorCode(MvcResult result) {
        return body(result).get("error").get("code").asText();
    }

    private long countStatus(List<MvcResult> results, int status) {
        return results.stream().filter(result -> result.getResponse().getStatus() == status).count();
    }

    // ---- DB ----

    private int currentQuantity(UUID inventoryId) {
        return queryForObject("SELECT quantity FROM hub_service.p_inventories WHERE inventory_id = ?", Integer.class, inventoryId);
    }

    private int countInventories(UUID productId) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventories WHERE product_id = ?", Integer.class, productId);
    }

    private UUID inventoryIdOf(UUID productId) {
        return queryForObject("SELECT inventory_id FROM hub_service.p_inventories WHERE product_id = ?", UUID.class, productId);
    }

    private int countInventoryLogs(UUID inventoryId, String changeType) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventory_logs WHERE inventory_id = ? AND change_type = ?",
                Integer.class, inventoryId, changeType);
    }

    // 로그를 처음부터 더하고 빼서 계산한 수량. 변경이 하나도 빠지거나 덮어써지지 않았다면 현재 수량과 같다
    private int quantityFromLogs(UUID inventoryId) {
        return queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN change_type IN ('INBOUND', 'RESTORE', 'ADJUST_INCREASE') THEN quantity
                                         ELSE -quantity END), 0)
                FROM hub_service.p_inventory_logs WHERE inventory_id = ?
                """, Integer.class, inventoryId);
    }
}
