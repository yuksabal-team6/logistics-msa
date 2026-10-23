package com.yuksabal.order.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.notification.SlackNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @Autowired
    private OrderOutboxPublisher publisher;
    @Autowired
    private KafkaTemplate<String, Object> realTemplate;

    // 브로커 장애 흉내: true인 동안 발행이 실패함 (Embedded Kafka(KRaft)는 브로커를 멈췄다 다시 켜는 기능이 없음)
    //  발행기의 KafkaTemplate을 테스트 동안만 대리 객체로 바꿔 끼움 (KafkaTemplate은 기본 생성자가 없어 @SpyBean 불가)
    // 실제 브로커 정지/재시작은 docker에서 따로 확인 (성능 테스트 보고서 E2: Kafka 정지 중 주문 50건 → 재시작 후 50건 발행, 중복 0)
    private final AtomicBoolean brokerDown = new AtomicBoolean(false);
    private KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        brokerDown.set(false);
        kafkaTemplate = mock(KafkaTemplate.class);
        doAnswer(invocation -> brokerDown.get()
                ? CompletableFuture.failedFuture(new KafkaException("broker down (테스트)"))
                : realTemplate.send(invocation.<String>getArgument(0), invocation.<String>getArgument(1), invocation.getArgument(2)))
                .when(kafkaTemplate).send(anyString(), anyString(), any());
        ReflectionTestUtils.setField(publisher, "kafkaTemplate", kafkaTemplate);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(publisher, "kafkaTemplate", realTemplate);
    }

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

    @Test
    void 브로커_장애_중에는_미발행으로_남고_복구되면_같은_이벤트가_발행되어_리스너까지_도착() throws Exception {
        brokerDown.set(true);
        UUID orderId = UUID.randomUUID();
        OrderEvent event = new OrderEvent(OrderEventType.MANUAL_ACTION_REQUIRED, orderId, "ORD-20261023-ABCDEF654321",
                OrderStatus.FAILED, CompensationStatus.FAILED, 5, 12_000L, 60_000L, null,
                null, null, null, null, "브로커 장애 중 저장", LocalDateTime.now());
        OrderOutbox saved = outboxRepository.save(OrderOutbox.of(event, objectMapper.writeValueAsString(event)));

        // 장애 중: 발행기는 시도하지만(폴링 200ms) 실패 → 미발행 유지, Slack 호출 없음
        verify(kafkaTemplate, timeout(5_000).atLeastOnce()).send(eq(OrderEvent.TOPIC), eq(orderId.toString()), any());
        verify(slackNotifier, after(1_500).never()).notifyManualAction(argThat((OrderEvent received) -> received.orderId().equals(orderId)));
        assertThat(outboxRepository.findById(saved.getId()).map(OrderOutbox::isPublished)).contains(false);

        // 복구: 같은 행이 다음 회차에 발행되고 리스너까지 도착
        brokerDown.set(false);
        verify(slackNotifier, timeout(10_000)).notifyManualAction(argThat((OrderEvent received) ->
                received.orderId().equals(orderId) && "브로커 장애 중 저장".equals(received.reason())));
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
