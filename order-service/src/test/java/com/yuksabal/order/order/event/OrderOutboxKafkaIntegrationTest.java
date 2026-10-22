package com.yuksabal.order.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.notification.SlackNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// outbox 저장 → 스케줄러 발행 → 리스너 → Slack 알림 호출까지 테스트용 Kafka로 확인 (#198 3단계)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "order.outbox.enabled=true",
        "order.outbox.poll-interval-ms=200"
})
@EmbeddedKafka(partitions = 1, topics = OrderEvent.TOPIC)
@ActiveProfiles("test")
class OrderOutboxKafkaIntegrationTest {

    @Autowired
    private OrderOutboxRepository outboxRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private SlackNotifier slackNotifier;

    @Test
    void outbox에_저장한_이벤트를_스케줄러가_발행하고_리스너가_수동_처리_알림을_보냄() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderEvent event = new OrderEvent(OrderEventType.MANUAL_ACTION_REQUIRED, orderId, "ORD-20261022-ABCDEF123456",
                OrderStatus.FAILED, CompensationStatus.FAILED, 5, 12_000L, 60_000L, null,
                null, null, null, null, "재고 복원 실패", LocalDateTime.now());
        OrderOutbox saved = outboxRepository.save(OrderOutbox.of(event, objectMapper.writeValueAsString(event)));

        // 스케줄러, 리스너 모두 다른 스레드에서 돌아서 최대 10초 기다림
        verify(slackNotifier, timeout(10_000)).notifyManualAction(argThat((OrderEvent received) ->
                received.orderId().equals(orderId) && "재고 복원 실패".equals(received.reason())));
        assertThat(waitPublished(saved.getId())).isTrue();
    }

    // 발행 시각 기록은 Slack 호출과 다른 스레드라 잠깐 기다림
    private boolean waitPublished(UUID outboxId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (outboxRepository.findById(outboxId).map(OrderOutbox::isPublished).orElse(false)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
