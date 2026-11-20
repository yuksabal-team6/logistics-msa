package com.yuksabal.order.order.event;

import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.notification.SlackNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// 테스트용 Kafka(EmbeddedKafka)를 띄워 발행 → 리스너 → Slack 알림 호출까지 실제로 확인 (#198)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true"
})
@EmbeddedKafka(partitions = 1, topics = OrderEvent.TOPIC)
@ActiveProfiles("test")
class OrderEventKafkaIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean
    private SlackNotifier slackNotifier;

    @Test
    void 발행한_주문_확정_이벤트를_리스너가_받아_주문_접수_알림을_보냄() {
        UUID orderId = UUID.randomUUID();
        OrderEvent event = new OrderEvent(OrderEventType.ORDER_CONFIRMED, orderId, "ORD-20261020-ABCDEF123456",
                OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED, 5, 12_000L, 60_000L, "신선도 유지 요청",
                "생물 고등어", "공급 업체", "요청 업체", "U12345678", null, LocalDateTime.now());

        kafkaTemplate.send(OrderEvent.TOPIC, orderId.toString(), event);

        // 리스너는 다른 스레드에서 비동기로 받으니 최대 10초 기다림
        verify(slackNotifier, timeout(10_000)).notifyOrderCreated(argThat((OrderEvent received) ->
                received.orderId().equals(orderId) && "U12345678".equals(received.ordererSlackId())));
    }
}
