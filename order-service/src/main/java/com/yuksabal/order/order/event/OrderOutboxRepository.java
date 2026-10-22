package com.yuksabal.order.order.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderOutboxRepository extends JpaRepository<OrderOutbox, UUID> {

    // 아직 안 보낸 이벤트를 오래된 순으로 최대 100개
    List<OrderOutbox> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
