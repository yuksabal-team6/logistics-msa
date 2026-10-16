package com.yuksabal.hub.inventory.repository;

import com.yuksabal.hub.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface InventoryRepository extends
        JpaRepository<Inventory, UUID>,
        JpaSpecificationExecutor<Inventory> {

    // 상품 재고는 업체 관리 허브 한 곳에만 생겨 보통 0~1건이다.
    // 업체 허브가 바뀌면 2건이 될 수 있어 Optional 대신 List로 받는다 (Optional이면 2건일 때 500)
    List<Inventory> findByProductId(UUID productId);
}
