package com.yuksabal.hub.inventory.domain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 재고 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다.
// hub.id는 FK 컬럼이라 허브 테이블과 조인하지 않는다.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InventorySpecification {

    // 조건 없으면 전체 / 허브만 / 상품만 / 둘 다(최대 1건)
    public static Specification<Inventory> search(UUID hubId, UUID productId) {
        return Specification
                .where(hubIdEquals(hubId))
                .and(productIdEquals(productId));
    }

    public static Specification<Inventory> hubIdEquals(UUID hubId) {
        return (root, query, cb) ->
                hubId == null
                        ? null
                        : cb.equal(root.get("hub").get("id"), hubId);
    }

    public static Specification<Inventory> productIdEquals(UUID productId) {
        return (root, query, cb) ->
                productId == null
                        ? null
                        : cb.equal(root.get("productId"), productId);
    }
}
