package com.yuksabal.hub.inventory.repository;

import com.yuksabal.hub.inventory.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository extends
        JpaRepository<Inventory, UUID>,
        JpaSpecificationExecutor<Inventory> {

    // 상품 재고는 업체 관리 허브 한 곳에만 생겨 보통 0~1건이다.
    // 업체 허브가 바뀌면 2건이 될 수 있어 Optional 대신 List로 받는다 (Optional이면 2건일 때 500)
    List<Inventory> findByProductId(UUID productId);

    // 한 허브에 같은 상품 재고는 하나다
    Optional<Inventory> findByHubIdAndProductId(UUID hubId, UUID productId);

    // 주문 차감·복원이 로그를 확인하기 전에 재고 행을 잠근다 (SELECT ... FOR UPDATE, 비관적 락)
    // 같은 재고의 차감·복원은 여기서 줄을 서서, 뒤에 온 요청은 앞 요청이 커밋한 로그를 보고 판단한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.hub.id = :hubId AND i.productId = :productId")
    Optional<Inventory> findByHubIdAndProductIdForUpdate(@Param("hubId") UUID hubId,
                                                         @Param("productId") UUID productId);

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

    // 남은 수량이 차감할 수량 이상일 때만 차감한다 (결정 #14). 수정한 행 수를 반환하고, 0이면 재고가 없거나 부족한 것
    // 벌크 UPDATE는 JPA Auditing을 거치지 않아 updatedAt·updatedBy를 직접 넣고, 삭제된 재고 제외 조건도 명시한다
    // 잠금 조회로 불러온 엔티티에 차감 전 수량이 남지 않도록 실행 후 영속성 컨텍스트를 비운다
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Inventory i
            SET i.quantity = i.quantity - :quantity, i.updatedAt = :now, i.updatedBy = :userId
            WHERE i.hub.id = :hubId
              AND i.productId = :productId
              AND i.quantity >= :quantity
              AND i.deletedAt IS NULL
            """)
    int decreaseQuantityIfEnough(@Param("hubId") UUID hubId,
                                 @Param("productId") UUID productId,
                                 @Param("quantity") int quantity,
                                 @Param("now") LocalDateTime now,
                                 @Param("userId") UUID userId);
}
