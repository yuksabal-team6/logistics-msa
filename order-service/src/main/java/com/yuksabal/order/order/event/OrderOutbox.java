package com.yuksabal.order.order.event;

import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

// 주문 이벤트 Outbox
// 주문 상태 변경과 같은 트랜잭션에서 저장 → OrderOutboxPublisher가 주기적으로 읽어 Kafka로 발행
// 발행 후에도 행은 남기고 publishedAt만 기록 (저장 건수와 발행 건수를 비교해 유실 여부 확인)
// 아직 전송하지 않은 이벤트를 보관한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "p_order_outbox")
public class OrderOutbox extends BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "outbox_id")
    private UUID id;

    // Kafka 메시지 key. 같은 주문의 이벤트는 같은 파티션으로 가서 순서가 지켜짐
    @Column(nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OrderEventType eventType;

    // OrderEvent JSON
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    // Kafka 발행 성공 시각. null이면 안 보냄
    private LocalDateTime publishedAt;

    private OrderOutbox(OrderEvent event, String payload) {
        this.id = UUID.randomUUID();
        this.orderId = event.orderId();
        this.eventType = event.eventType();
        this.payload = payload;
    }

    // payload는 호출하는 쪽이 ObjectMapper로 만든 OrderEvent JSON
    public static OrderOutbox of(OrderEvent event, String payload) {
        return new OrderOutbox(event, payload);
    }

    /**
     * 발행 완료 시각을 현재 시각으로 기록한다.
     */
    public void markPublished() {
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * Outbox 이벤트의 발행 완료 여부를 확인한다.(slack 알림은 논외)
     *
     * @return 발행 완료 시 true, 미완료 시 false
     */
    public boolean isPublished() {
        return publishedAt != null;
    }

    // ID를 앱에서 만들어서, save() 때 기존 행으로 보고 merge하지 않도록 createdAt으로 새 행 판단 (Order와 같음)
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
