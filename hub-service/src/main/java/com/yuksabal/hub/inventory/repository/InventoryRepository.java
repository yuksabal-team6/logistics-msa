package com.yuksabal.hub.inventory.repository;

import com.yuksabal.hub.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface InventoryRepository extends
        JpaRepository<Inventory, UUID>,
        JpaSpecificationExecutor<Inventory> {

    // 상품 재고는 업체 관리 허브 한 곳에만 생겨 보통 0~1건이다.
    // 업체 허브가 바뀌면 2건이 될 수 있어 Optional 대신 List로 받는다 (Optional이면 2건일 때 500)
    List<Inventory> findByProductId(UUID productId);

    // 현재 수량이 expectedQuantity일 때만 바꾼다 (CAS). 수정한 행 수를 반환하고, 0이면 재고가 없거나 수량이 바뀐 것
    // 벌크 UPDATE는 JPA Auditing을 거치지 않아 updatedAt·updatedBy를 직접 넣고, 삭제된 재고 제외 조건도 명시한다
    @Modifying
    @Query("""
            UPDATE Inventory i
            SET i.quantity = :quantity, i.updatedAt = :now, i.updatedBy = :userId
            WHERE i.id = :inventoryId
              AND i.quantity = :expectedQuantity
              AND i.deletedAt IS NULL
            """)
    int updateQuantityIfMatches(@Param("inventoryId") UUID inventoryId,
                                @Param("expectedQuantity") int expectedQuantity,
                                @Param("quantity") int quantity,
                                @Param("now") LocalDateTime now,
                                @Param("userId") UUID userId);
}
