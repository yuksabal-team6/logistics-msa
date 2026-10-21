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

// 주문 재고 차감·복원에 요청이 동시에 몰려도 재고 수량과 로그가 정확한지 확인한다
// 순차 요청(StockInternalIntegrationTest)은 앞 요청이 커밋된 뒤에 오므로 재고 행 잠금 대기와
// 로그 유니크 위반 처리 경로를 타지 않는다. 여기서는 스레드 여러 개가 order 대신 내부 API를 동시에 호출한다
class StockConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final int THREAD_COUNT = 20;

    @Nested
    class 주문_차감 {

        @Test
        void 서로_다른_주문이_동시에_차감해도_차감이_하나도_사라지지_않는다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 50);

            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> decrease(productId, 1, UUID.randomUUID()));

            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            assertThat(currentQuantity(inventoryId)).isEqualTo(50 - THREAD_COUNT);
            assertThat(countInventoryLogs(inventoryId, "DECREASE")).isEqualTo(THREAD_COUNT);
        }

        @Test
        void 재고보다_많은_주문이_동시에_차감하면_재고만큼만_성공하고_나머지는_409() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);

            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> decrease(productId, 1, UUID.randomUUID()));

            assertThat(countStatus(results, 200)).isEqualTo(10);
            assertThat(countStatus(results, 409)).isEqualTo(THREAD_COUNT - 10);
            assertThat(currentQuantity(inventoryId)).isZero();
            assertThat(countInventoryLogs(inventoryId, "DECREASE")).isEqualTo(10);
        }

        @Test
        void 같은_주문이_동시에_차감하면_한_번만_차감하고_모두_200() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();

            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> decrease(productId, 3, orderId));

            // 먼저 커밋된 차감 결과로 응답하므로 모든 응답의 남은 수량이 같다
            assertThat(results).allSatisfy(result -> {
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                assertThat(data(result).get("remainingQuantity").asInt()).isEqualTo(7);
            });
            assertThat(currentQuantity(inventoryId)).isEqualTo(7);
            assertThat(countOrderLogs(orderId)).isEqualTo(1);
        }
    }

    @Nested
    class 주문_복원 {

        @Test
        void 같은_주문이_동시에_복원하면_한_번만_복원하고_모두_200() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 10);
            UUID orderId = UUID.randomUUID();
            runConcurrently(1, i -> decrease(productId, 3, orderId));

            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> restore(productId, 3, orderId));

            // 먼저 커밋된 복원 결과로 응답하므로 모든 응답의 복원 수량이 같다
            assertThat(results).allSatisfy(result -> {
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                assertThat(data(result).get("restoredQuantity").asInt()).isEqualTo(3);
            });
            assertThat(currentQuantity(inventoryId)).isEqualTo(10);
            assertThat(countOrderLogs(orderId)).isEqualTo(2);
        }
    }

    @Nested
    class 차감_복원_동시 {

        @Test
        void 같은_주문의_차감과_복원이_동시에_와도_처리_순서와_관계없이_재고가_원래대로다() throws Exception {
            UUID productId = UUID.randomUUID();
            UUID inventoryId = inbound(productId, 100);
            // 주문마다 차감·복원 한 쌍을 동시에 보내 두 순서(차감 → 복원, 복원 → 차감)가 섞여 나오게 한다
            List<UUID> orderIds = new ArrayList<>();
            for (int i = 0; i < THREAD_COUNT / 2; i++) {
                orderIds.add(UUID.randomUUID());
            }

            List<MvcResult> results = runConcurrently(THREAD_COUNT, i -> {
                UUID orderId = orderIds.get(i / 2);
                return i % 2 == 0 ? decrease(productId, 3, orderId) : restore(productId, 3, orderId);
            });

            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            assertThat(currentQuantity(inventoryId)).isEqualTo(100);
            // 차감이 먼저면 DECREASE 3 + RESTORE 3 (로그 2개), 복원이 먼저면 RESTORE 0만 남고 늦은 차감은 막힌다 (로그 1개)
            for (UUID orderId : orderIds) {
                Integer restored = logQuantity(orderId, "RESTORE");
                if (countOrderLogs(orderId) == 2) {
                    assertThat(restored).isEqualTo(3);
                } else {
                    assertThat(restored).isZero();
                }
            }
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

    private MockHttpServletRequestBuilder decrease(UUID productId, int quantity, UUID orderId) {
        return stockRequest("/internal/stocks/decrease", productId, quantity, orderId);
    }

    private MockHttpServletRequestBuilder restore(UUID productId, int quantity, UUID orderId) {
        return stockRequest("/internal/stocks/restore", productId, quantity, orderId);
    }

    private MockHttpServletRequestBuilder stockRequest(String url, UUID productId, int quantity, UUID orderId) {
        try {
            return postJson(url, Map.of("hubId", HUB_ID, "productId", productId, "quantity", quantity, "orderId", orderId));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode data(MvcResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long countStatus(List<MvcResult> results, int status) {
        return results.stream().filter(result -> result.getResponse().getStatus() == status).count();
    }

    private int currentQuantity(UUID inventoryId) {
        return queryForObject("SELECT quantity FROM hub_service.p_inventories WHERE inventory_id = ?", Integer.class, inventoryId);
    }

    private int countInventoryLogs(UUID inventoryId, String changeType) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventory_logs WHERE inventory_id = ? AND change_type = ?",
                Integer.class, inventoryId, changeType);
    }

    private Integer logQuantity(UUID orderId, String changeType) {
        return queryForObject("SELECT quantity FROM hub_service.p_inventory_logs WHERE order_id = ? AND change_type = ?",
                Integer.class, orderId, changeType);
    }

    private int countOrderLogs(UUID orderId) {
        return queryForObject("SELECT count(*) FROM hub_service.p_inventory_logs WHERE order_id = ?", Integer.class, orderId);
    }
}
