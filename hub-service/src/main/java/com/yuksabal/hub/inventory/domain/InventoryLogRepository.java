package com.yuksabal.hub.inventory.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryLogRepository extends
        JpaRepository<InventoryLog, UUID>,
        JpaSpecificationExecutor<InventoryLog> {

    // 주문 차감·복원 멱등성 확인. 한 주문의 로그는 DECREASE·RESTORE 각 0~1건이라 최대 2건 (결정 #18)
    // 두 유형을 한 번에 조회한다 (#273). 복원 시 차감한 재고의 허브·상품을 확인하므로 재고를 함께 조회한다
    @EntityGraph(attributePaths = "inventory")
    List<InventoryLog> findByOrderId(UUID orderId);

    // 동시 중복 요청의 유니크 위반 후, 트랜잭션 밖에서 먼저 처리된 결과로 응답을 만들 때 쓴다
    // (order_id, change_type) 부분 유니크 인덱스라 0~1건. 트랜잭션 밖이라 재고를 함께 조회한다
    @EntityGraph(attributePaths = "inventory")
    Optional<InventoryLog> findByOrderIdAndChangeType(UUID orderId, InventoryChangeType changeType);

    // 로그 목록 응답에 재고의 허브·상품이 들어가 재고를 함께 조회한다 (로그마다 재고 조회 쿼리가 나가는 N+1 방지)
    // 다대일 조인이라 페이지 크기만큼만 가져오고, count 쿼리에는 적용되지 않는다
    @Override
    @EntityGraph(attributePaths = "inventory")
    Page<InventoryLog> findAll(Specification<InventoryLog> spec, Pageable pageable);
}
