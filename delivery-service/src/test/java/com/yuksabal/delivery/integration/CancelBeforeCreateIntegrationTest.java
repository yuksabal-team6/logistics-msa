package com.yuksabal.delivery.integration;

import com.yuksabal.delivery.delivery.application.DeliveryService;
import com.yuksabal.delivery.delivery.presentation.dto.request.DeliveryCreateRequest;
import com.yuksabal.delivery.delivery.presentation.dto.response.DeliveryCreateResponse;
import com.yuksabal.delivery.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// #302 취소가 생성보다 먼저 처리될 때 늦은 생성이 배송을 남기지 않는지 (실제 PostgreSQL + uk_deliveries_order_id)
// 기대값은 수정 후 동작이다. 수정 전 코드(비교군)에서는 1)~4)가 실패하고, 3-b(대조)는 통과해야 한다
// order의 생성 포기 → 취소 → 늦은 생성 커밋 순서를 서비스 호출 순서와 latch로 만든다 (HTTP 계층은 거치지 않는다)
class CancelBeforeCreateIntegrationTest extends IntegrationTestSupport {

    private static final String CANCEL_THREAD = "cancel-302";

    @Autowired
    private DeliveryService deliveryService;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    // 1) 취소 먼저 → 생성 나중
    // 수정 후: 취소가 표식(CANCELLED)을 남기고, 생성은 첫 findByOrderId에서 표식을 반환해 허브를 부르지 않는다
    @Test
    void 시나리오1_취소가_먼저_처리되고_생성이_나중에_오면_활성_배송이_남지_않고_허브를_부르지_않는다() {
        UUID orderId = UUID.randomUUID();
        HubGate hub = passHubRoute();

        deliveryService.cancelDelivery(orderId);
        DeliveryCreateResponse created = deliveryService.createDelivery(request(orderId));

        record("1", orderId, "반환 상태=" + statusOf(created.deliveryId()) + ", 허브 호출=" + hub.calls());
        assertThat(activeDeliveryCount(orderId)).as("활성 배송 수 %s", rowsOf(orderId)).isZero();
        assertThat(rowsOf(orderId)).as("order_id 행 수").hasSize(1);
        assertThat(hub.calls()).as("허브 호출 횟수").isZero();
    }

    // 2) 생성이 허브 호출에서 붙잡힌 사이 취소 → 생성 커밋
    // 수정 후: 취소가 표식을 저장하고, 생성은 저장 시 유니크 위반 → 기존 catch가 표식(CANCELLED)을 반환한다
    @Test
    void 시나리오2_생성이_허브_응답을_기다리는_사이_취소되면_생성은_CANCELLED_배송을_반환한다() throws Exception {
        UUID orderId = UUID.randomUUID();
        HubGate hub = holdHubRoute(1);

        Future<DeliveryCreateResponse> creating = executor.submit(() -> deliveryService.createDelivery(request(orderId)));
        hub.awaitEntered();                       // 생성이 findByOrderId(빈 결과)를 지나 허브 호출에 멈춤

        deliveryService.cancelDelivery(orderId);  // order가 포기하고 보낸 취소가 먼저 처리됨
        hub.open(0);                              // 허브가 늦게 응답 → 생성 저장·커밋
        DeliveryCreateResponse created = creating.get(WAIT_SECONDS, TimeUnit.SECONDS);

        String returnedStatus = statusOf(created.deliveryId());
        record("2", orderId, "반환 상태=" + returnedStatus);
        assertThat(returnedStatus).as("생성이 반환한 배송 상태").isEqualTo("CANCELLED");
        assertThat(activeDeliveryCount(orderId)).as("활성 배송 수 %s", rowsOf(orderId)).isZero();
        assertThat(rowsOf(orderId)).as("order_id 행 수").hasSize(1);
    }

    // 3-a) 같은 주문의 생성 요청 3건(order의 Feign 재시도)이 모두 허브에서 붙잡힌 사이 취소 → 3건이 차례로 커밋
    // 수정 후: 3건 모두 유니크 위반 → 표식(CANCELLED) 반환, 활성 배송 없음, 행 1개
    @Test
    void 시나리오3a_생성_3건이_모두_취소_뒤에_커밋돼도_활성_배송이_남지_않는다() throws Exception {
        UUID orderId = UUID.randomUUID();
        HubGate hub = holdHubRoute(3);
        CompletionService<DeliveryCreateResponse> creates = startCreates(orderId, 3);
        hub.awaitEntered();

        deliveryService.cancelDelivery(orderId);
        List<DeliveryCreateResponse> responses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {             // 하나씩 풀어 커밋 순서를 고정한다
            hub.open(i);
            responses.add(takeNext(creates));
        }

        record("3-a", orderId, "반환 상태=" + responses.stream().map(r -> statusOf(r.deliveryId())).toList());
        assertThat(activeDeliveryCount(orderId)).as("활성 배송 수 %s", rowsOf(orderId)).isZero();
        assertThat(rowsOf(orderId)).as("order_id 행 수").hasSize(1);
        assertThat(responses).extracting(DeliveryCreateResponse::deliveryId).as("3건이 같은 배송을 반환").containsOnly(responses.get(0).deliveryId());
    }

    // 3-b) 대조: 생성 1건은 취소 전에 커밋, 나머지 2건은 취소 뒤에 커밋
    // 취소가 이미 저장된 배송을 찾아 취소하므로 수정 전 코드에서도 통과해야 한다 (테스트 장치가 맞는지 확인용)
    @Test
    void 시나리오3b_대조_생성_1건이_취소_전에_커밋되면_지금_코드도_활성_배송이_남지_않는다() throws Exception {
        UUID orderId = UUID.randomUUID();
        HubGate hub = holdHubRoute(3);
        CompletionService<DeliveryCreateResponse> creates = startCreates(orderId, 3);
        hub.awaitEntered();

        hub.open(0);
        DeliveryCreateResponse first = takeNext(creates);   // 취소 전 커밋 (READY)
        deliveryService.cancelDelivery(orderId);
        hub.open(1);
        hub.open(2);
        List<DeliveryCreateResponse> later = List.of(takeNext(creates), takeNext(creates));

        record("3-b", orderId, "첫 생성 후 취소, 나중 2건 반환 상태=" + later.stream().map(r -> statusOf(r.deliveryId())).toList());
        assertThat(activeDeliveryCount(orderId)).as("활성 배송 수 %s", rowsOf(orderId)).isZero();
        assertThat(rowsOf(orderId)).as("order_id 행 수").hasSize(1);
        assertThat(later).extracting(DeliveryCreateResponse::deliveryId).containsOnly(first.deliveryId());
    }

    // 4) 취소가 빈 결과를 본 뒤, 표식을 저장하기 전에 생성이 커밋
    // 재현 방법: DeliveryRepository(SpyBean)의 findByOrderId가 취소 스레드에서 처음 불렸을 때, 실제 조회 결과를 받은 직후 멈춘다
    //   그 사이 생성(허브 즉시 응답)을 끝까지 커밋하고 취소를 풀어 준다
    // 수정 후: 표식 저장이 유니크 위반 → 새 트랜잭션에서 다시 읽어 READY 배송을 취소 → 활성 배송 없음, 행 1개
    // 전제: 수정 코드도 취소의 조회를 deliveryRepository.findByOrderId로 한다. 다른 메서드로 바꾸면 멈춤 지점이 안 걸려 아래 확인에서 실패한다
    @Test
    void 시나리오4_취소가_빈_결과를_본_뒤_표식_저장_전에_생성이_커밋돼도_활성_배송이_남지_않는다() throws Exception {
        UUID orderId = UUID.randomUUID();
        HubGate hub = passHubRoute();
        CountDownLatch cancelLookedUp = new CountDownLatch(1);
        CountDownLatch resumeCancel = new CountDownLatch(1);
        AtomicBoolean firstCancelLookup = new AtomicBoolean(true);
        AtomicReference<Boolean> cancelSawEmpty = new AtomicReference<>();
        // SpyBean(리포지토리 = JDK 프록시)은 실제 객체로 위임하는 mock이라 callRealMethod()가 안 된다 → 기본 응답(위임)을 그대로 부른다
        Answer<?> delegate = Mockito.mockingDetails(deliveryRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = delegate.answer(invocation);
            if (CANCEL_THREAD.equals(Thread.currentThread().getName()) && firstCancelLookup.compareAndSet(true, false)) {
                cancelSawEmpty.set(((Optional<?>) result).isEmpty());
                cancelLookedUp.countDown();
                if (!resumeCancel.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트가 취소를 풀어 주지 않았다");
                }
            }
            return result;
        }).when(deliveryRepository).findByOrderId(any());

        ExecutorService cancelExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, CANCEL_THREAD));
        try {
            Future<?> cancelling = cancelExecutor.submit(() -> deliveryService.cancelDelivery(orderId));
            assertThat(cancelLookedUp.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("취소가 findByOrderId로 조회하지 않아 멈춤 지점이 걸리지 않았다").isTrue();
            assertThat(cancelSawEmpty.get()).as("취소가 본 조회 결과가 비어 있음").isTrue();

            DeliveryCreateResponse created = deliveryService.createDelivery(request(orderId));  // 표식 저장 전에 생성 커밋
            resumeCancel.countDown();
            cancelling.get(WAIT_SECONDS, TimeUnit.SECONDS);

            record("4", orderId, "생성이 반환한 배송의 최종 상태=" + statusOf(created.deliveryId()) + ", 허브 호출=" + hub.calls());
        } finally {
            resumeCancel.countDown();
            cancelExecutor.shutdownNow();
        }
        assertThat(activeDeliveryCount(orderId)).as("활성 배송 수 %s", rowsOf(orderId)).isZero();
        assertThat(rowsOf(orderId)).as("order_id 행 수").hasSize(1);
    }

    // ---- 도우미 ----

    private CompletionService<DeliveryCreateResponse> startCreates(UUID orderId, int count) {
        CompletionService<DeliveryCreateResponse> service = new ExecutorCompletionService<>(executor);
        Callable<DeliveryCreateResponse> create = () -> deliveryService.createDelivery(request(orderId));
        for (int i = 0; i < count; i++) {
            service.submit(create);
        }
        return service;
    }

    // 다음으로 끝난 생성 요청. 생성이 예외로 끝나면 여기서 그대로 실패한다
    private DeliveryCreateResponse takeNext(CompletionService<DeliveryCreateResponse> service) throws Exception {
        Future<DeliveryCreateResponse> done = service.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(done).as("생성 요청이 제한 시간 안에 끝남").isNotNull();
        return done.get();
    }

    // 출발·도착 허브가 달라 허브 경로를 조회하는 요청 (같은 허브면 허브를 부르지 않는다)
    private DeliveryCreateRequest request(UUID orderId) {
        return new DeliveryCreateRequest(orderId, DEPARTURE_HUB_ID, DESTINATION_HUB_ID, UUID.randomUUID(),
                "서울특별시 송파구 송파대로 55", "육사발", null, UUID.randomUUID(), UUID.randomUUID());
    }

    // 결과 표를 만들기 위해 assert 전에 실제로 남은 행을 기록한다 (테스트 리포트 system-out)
    private void record(String scenario, UUID orderId, String detail) {
        List<String> statuses = rowsOf(orderId).stream()
                .map(row -> row.get("delivery_status") + (row.get("deleted_at") == null ? "" : "(deleted)"))
                .toList();
        System.out.printf("[#302 결과] 시나리오=%s, order_id 행 수=%d, 상태=%s, 활성 배송=%d, %s%n",
                scenario, statuses.size(), statuses, activeDeliveryCount(orderId), detail);
    }
}
