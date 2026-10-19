package com.yuksabal.order.order.repository;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.order.order.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    // 멱등성 확인. 삭제된 주문은 @SQLRestriction으로 제외
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * 주문 ID로 주문을 조회한다.
     * 없거나 삭제된 주문이면 예외를 발생한다.
     *
     * @param orderId 존재 여부를 확인할 주문 ID
     * @return 존재하는 주문 객체
     */
    default Order findByIdOrThrow(UUID orderId) {
        return findById(orderId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 주문입니다.", "orderId"));
    }

    // 행 잠금 조회 (SELECT ... FOR UPDATE). 같은 주문 상태를 바꾸는 동시 요청을 한 번에 하나씩 처리
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);

    // 행 잠금 조회, 없거나 삭제된 주문이면 404
    default Order findByIdForUpdateOrThrow(UUID orderId) {
        return findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 주문입니다.", "orderId"));
    }
}
