package com.yuksabal.hub.inventory.repository;

import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InventoryLogRepository extends JpaRepository<InventoryLog, UUID> {

    // 주문 차감·복원 멱등성 확인. (order_id, change_type) 부분 유니크 인덱스라 0~1건 (결정 #18)
    // 트랜잭션 밖(동시 중복 처리)에서도 응답을 만들 수 있게 재고를 함께 조회한다
    @EntityGraph(attributePaths = "inventory")
    Optional<InventoryLog> findByOrderIdAndChangeType(UUID orderId, InventoryChangeType changeType);
}
