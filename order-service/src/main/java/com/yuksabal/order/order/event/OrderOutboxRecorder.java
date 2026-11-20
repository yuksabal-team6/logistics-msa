package com.yuksabal.order.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 주문 이벤트를 Outbox 테이블에 저장한다.
 * <p>
 * 호출부의 트랜잭션 내에서 실행되어야 주문 상태 변경과 함께 커밋되거나 롤백된다.
 * <p>
 * 실제 Kafka 이벤트 발행은 OrderOutboxPublisher가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class OrderOutboxRecorder {

    private final OrderOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * 아웃박스에 OrderEvent를 저장한다.
     * 주문 상태 변경과 함께 커밋되거나 롤백되도록 호출부의 트랜잭션 내에서 실행해야 한다.
     *
     * @param event 서비스에서 만들어진 OrderEvent 객체
     */
    public void record(OrderEvent event) {
        outboxRepository.save(OrderOutbox.of(event, toJson(event)));
    }

    /**
     * outbox 에 이벤트 내용을 저장하기 위해 이벤트를 직렬화한다.
     *
     * @param event 서비스에서 만들어진 OrderEvent 객체
     * @return 직렬화된 Json 문자열
     */
    private String toJson(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // record라 직렬화 실패는 거의 없음. 실패하면 트랜잭션째 롤백되도록 런타임 예외로
            throw new IllegalStateException("주문 이벤트 JSON 변환 실패 orderId=" + event.orderId(), e);
        }
    }
}
