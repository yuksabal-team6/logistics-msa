package com.yuksabal.order.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

// outbox(p_order_outbox)의 안 보낸 이벤트를 주기적으로 Kafka order.events로 발행 (#198)
// Kafka가 죽어 있으면 발행이 실패해 outbox에 쌓이고, 살아나면 다음 회차에 오래된 것부터 보냄
// 발행 성공 후 publishedAt 기록 전에 서버가 죽으면 같은 이벤트가 한 번 더 갈 수 있음 (at-least-once, 중복 거르기는 추후)

/**
 * Outbox 테이블에 저장된 미발행 주문 이벤트를 Kafka로 전송한다.
 *
 * <p>이벤트 발행 흐름</p>
 * <ol>
 *     <li>Outbox 테이블에서 미발행 이벤트를 오래된 순서대로 조회한다.</li>
 *     <li>이벤트 데이터를 역직렬화하여 kafka order.events 토픽에 발행한다.</li>
 *     <li>발행 성공 시 publishedAt을 기록한다.</li>
 *     <li>발행 실패 시 미발행 상태 유지하여 다음 실행에서 재시도한다.</li>
 * </ol>
 *
 * <p>Kafka 발행 성공 후 DB 기록 전에 장애 발생하면
 * 같은 이벤트가 중복 발행될 수 있으므로 Consumer의 멱등 처리가 필요하다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxPublisher {

    // Kafka 응답 대기 상한. 넘으면 이번 회차 중단, 다음 회차에 다시 시도
    // producer의 delivery.timeout.ms(5초)보다 길게 둬서, producer가 먼저 성공 또는 실패를 확정한 결과를 받음
    //  (짧으면 우리가 포기한 메시지를 producer가 나중에 보내 중복 발행됨)
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OrderOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    // 한 회차에 읽어 보낼 최대 건수. 너무 크면 장애 복구 직후 한 회차가 길어지고, 너무 작으면 밀린 걸 다 보내는 데 오래 걸림
    @Value("${order.outbox.batch-size:100}")
    private int batchSize;

    // 이전 회차가 끝난 뒤 poll-interval-ms 만큼 쉬고 다시 실행 (회차끼리 겹치지 않음)

    /**
     * 안 보낸 이벤트를 찾아서 발행하는 메서드이다.
     * 이전 회차가 끝난 후 poll-interval-ms 만큼 기다렸다 다시 실행한다.
     */
    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        // published가 null인 미발행 아벤트를 오래된 순서로 최대 batch-size개 가져온다.
        List<OrderOutbox> pending = outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, batchSize));

        // 미발행 이벤트가 없으면 종료
        if (pending.isEmpty()) {
            return;
        }
        log.debug("[Outbox] 미발행 {}건 발행 시작", pending.size());

        // 조회한 이벤트를 하나씩 publish() 에 전달
        for (OrderOutbox outbox : pending) {
            if (!publish(outbox)) {
                // 중간에 실패하면 뒤 이벤트도 보내지 않고 다음 회차로 (같은 주문 이벤트 순서 유지)
                return;
            }
        }
    }

    /**
     * outbox 이벤트 1건을 Kafka에 발행하고,
     * 성공하면 Outbox 테이블에 발행 완료 시각을 기록한다.
     *
     * @param outbox 아웃박스 엔티티
     * @return 발행 성공 시 true, 실패 시 false
     */
    private boolean publish(OrderOutbox outbox) {
        try {
            OrderEvent event = objectMapper.readValue(outbox.getPayload(), OrderEvent.class);

            // kafka에 이벤트 발행
            kafkaTemplate.send(OrderEvent.TOPIC, outbox.getOrderId().toString(), event)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (JsonProcessingException e) {
            // payload가 깨진 이벤트는 다시 해도 실패 → 이번 회차 중단, 운영자가 행을 확인해야 함

            log.error("[Outbox] payload 해석 실패 outboxId={}, orderId={} → 발행 중단", outbox.getId(), outbox.getOrderId(), e);
            return false;
        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            log.warn("[Outbox] 발행 대기 중 인터럽트 outboxId={}", outbox.getId());
            return false;
        } catch (Exception e) {
            // Kafka 연결 실패, 타임아웃 등 → 다음 회차에 다시 시도

            log.warn("[Outbox] Kafka 발행 실패 outboxId={}, orderId={}, error={} → 다음 회차에 재시도",
                    outbox.getId(), outbox.getOrderId(), e.getClass().getSimpleName());
            return false;
        }

        // outbox 행 재조회(영속성) 후 발행 완료 시각 저장
        transactionTemplate.executeWithoutResult(status ->
                outboxRepository.findById(outbox.getId()).ifPresent(OrderOutbox::markPublished));
        log.debug("[Outbox] 발행 완료 outboxId={}, type={}, orderId={}", outbox.getId(), outbox.getEventType(), outbox.getOrderId());
        return true;
    }
}
