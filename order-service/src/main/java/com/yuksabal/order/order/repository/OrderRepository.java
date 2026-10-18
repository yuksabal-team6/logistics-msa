package com.yuksabal.order.order.repository;

import com.yuksabal.order.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    // 멱등성 확인. 삭제된 주문은 @SQLRestriction으로 제외
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
