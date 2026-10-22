package com.yuksabal.order.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OrderOutboxPublisherTest {

    private final ObjectMapper objectMapper = JacksonUtils.enhancedObjectMapper();
    private OrderOutboxRepository outboxRepository;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private OrderOutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxRepository = mock(OrderOutboxRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        // 트랜잭션 없이 안의 코드만 실행
        willAnswer(invocation -> {
            invocation.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).given(transactionTemplate).executeWithoutResult(any());
        publisher = new OrderOutboxPublisher(outboxRepository, kafkaTemplate, objectMapper, transactionTemplate);
    }

    @Test
    void 미발행_이벤트를_오래된_순으로_발행하고_발행_시각을_기록() throws Exception {
        OrderOutbox first = outbox();
        OrderOutbox second = outbox();
        givenPending(first, second);
        given(kafkaTemplate.send(eq(OrderEvent.TOPIC), anyString(), any())).willReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        verify(kafkaTemplate).send(eq(OrderEvent.TOPIC), eq(first.getOrderId().toString()), any(OrderEvent.class));
        verify(kafkaTemplate).send(eq(OrderEvent.TOPIC), eq(second.getOrderId().toString()), any(OrderEvent.class));
        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isTrue();
    }

    @Test
    void Kafka_발행이_실패하면_발행_시각을_남기지_않고_뒤_이벤트도_안_보냄() {
        OrderOutbox first = outbox();
        OrderOutbox second = outbox();
        givenPending(first, second);
        given(kafkaTemplate.send(eq(OrderEvent.TOPIC), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publishPending();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
        assertThat(first.isPublished()).isFalse();
        assertThat(second.isPublished()).isFalse();
    }

    @Test
    void payload가_깨졌으면_발행하지_않고_중단() {
        OrderOutbox broken = OrderOutbox.of(event(), "{not json");
        givenPending(broken);

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        assertThat(broken.isPublished()).isFalse();
    }

    @Test
    void 미발행_이벤트가_없으면_아무것도_안_함() {
        given(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private void givenPending(OrderOutbox... rows) {
        given(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(rows));
        for (OrderOutbox row : rows) {
            given(outboxRepository.findById(row.getId())).willReturn(Optional.of(row));
        }
    }

    private OrderOutbox outbox() {
        try {
            OrderEvent event = event();
            return OrderOutbox.of(event, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderEvent event() {
        return new OrderEvent(OrderEventType.MANUAL_ACTION_REQUIRED, UUID.randomUUID(), "ORD-20261022-ABCDEF123456",
                OrderStatus.FAILED, CompensationStatus.FAILED, 5, 12_000L, 60_000L, null,
                null, null, null, null, "재고 복원 실패", LocalDateTime.now());
    }
}
