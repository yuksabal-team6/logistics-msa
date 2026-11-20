package com.yuksabal.order.order.event;

import com.yuksabal.order.order.notification.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 주문 이벤트 order.events를 구독하여 이벤트 종류에 따라 Slack 알림을 전송한다.
 * <ul>
 *     <li>ORDER_CONFIRMED: 주문 확정 알림</li>
 *     <li>MANUAL_ACTION_REQUIRED: 수동 처리 요청 알림</li>
 *     <li>CHECK_NEEDED: 확인 필요 알림</li>
 * </ul>
 *
 * <p>이벤트에 알림 필요 정보가 포함되어 있어 주문 DB를 재조회하지 않는다.
 * <p>컨슈머 그룹: application.yml spring.kafka.consumer.group-id (order-slack-notifier)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final SlackNotifier slackNotifier;

    // 토픽 구독. 토픽에서 이벤트를 수신하면 handle()을 실행한다.
    @KafkaListener(topics = OrderEvent.TOPIC)
    public void handle(OrderEvent event) {
        log.debug("[주문 이벤트] 수신 type={}, orderId={}", event.eventType(), event.orderId());
        switch (event.eventType()) {
            case ORDER_CONFIRMED -> slackNotifier.notifyOrderCreated(event);
            case MANUAL_ACTION_REQUIRED -> slackNotifier.notifyManualAction(event);
            case CHECK_NEEDED -> slackNotifier.notifyCheckNeeded(event);
        }
    }
}
