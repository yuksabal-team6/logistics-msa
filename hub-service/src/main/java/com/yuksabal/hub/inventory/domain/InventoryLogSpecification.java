package com.yuksabal.hub.inventory.domain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 재고 로그 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다.
// inventory.id는 FK 컬럼이라 조인하지 않고, 허브 조건만 재고 테이블과 조인한다 (허브는 재고의 hub_id 컬럼)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InventoryLogSpecification {

    public static Specification<InventoryLog> search(UUID inventoryId, UUID hubId, UUID orderId,
                                                     InventoryChangeType changeType) {
        return Specification
                .where(inventoryIdEquals(inventoryId))
                .and(hubIdEquals(hubId))
                .and(orderIdEquals(orderId))
                .and(changeTypeEquals(changeType));
    }

    public static Specification<InventoryLog> inventoryIdEquals(UUID inventoryId) {
        return (root, query, cb) ->
                inventoryId == null
                        ? null
                        : cb.equal(root.get("inventory").get("id"), inventoryId);
    }

    public static Specification<InventoryLog> hubIdEquals(UUID hubId) {
        return (root, query, cb) ->
                hubId == null
                        ? null
                        : cb.equal(root.get("inventory").get("hubId"), hubId);
    }

    public static Specification<InventoryLog> orderIdEquals(UUID orderId) {
        return (root, query, cb) ->
                orderId == null
                        ? null
                        : cb.equal(root.get("orderId"), orderId);
    }

    public static Specification<InventoryLog> changeTypeEquals(InventoryChangeType changeType) {
        return (root, query, cb) ->
                changeType == null
                        ? null
                        : cb.equal(root.get("changeType"), changeType);
    }
}
