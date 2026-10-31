package com.yuksabal.order.order.event;

import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.notification.SlackNotifier;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 이벤트 종류별로 맞는 Slack 알림을 부르는지 (#198)
class OrderEventListenerTest {

    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final OrderEventListener listener = new OrderEventListener(slackNotifier);

    @Test
    void 주문_확정이면_주문_접수_알림() {
        OrderEvent event = event(OrderEventType.ORDER_CONFIRMED);

        listener.handle(event);

        verify(slackNotifier).notifyOrderCreated(event);
        verify(slackNotifier, never()).notifyManualAction(any(OrderEvent.class));
    }

    @Test
    void 수동_처리_필요면_수동_처리_알림() {
        OrderEvent event = event(OrderEventType.MANUAL_ACTION_REQUIRED);

        listener.handle(event);

        verify(slackNotifier).notifyManualAction(event);
        verify(slackNotifier, never()).notifyOrderCreated(any(OrderEvent.class));
    }

    @Test
    void 확인_필요면_확인_필요_알림() {
        OrderEvent event = event(OrderEventType.CHECK_NEEDED);

        listener.handle(event);

        verify(slackNotifier).notifyCheckNeeded(event);
        verify(slackNotifier, never()).notifyManualAction(any(OrderEvent.class));
    }

    private static OrderEvent event(OrderEventType type) {
        return new OrderEvent(type, UUID.randomUUID(), "ORD-20261020-ABCDEF123456", OrderStatus.CONFIRMED,
                CompensationStatus.NOT_REQUIRED, 5, 12_000L, 60_000L, null,
                "생물 고등어", "공급 업체", "요청 업체", "U12345678", "사유", LocalDateTime.now());
    }
}
