package com.yuksabal.hub.inventory.domain;

import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.SQLRestriction;

import java.util.Objects;
import java.util.UUID;

// 재고 변경 이력. 기록용이자 (order_id, change_type) 멱등성 키라 삭제하지 않는다 (결정 #18).
// 한 번 남긴 기록은 바꾸지 않는다. @Immutable이라 저장 후 값이 바뀌어도 변경 감지 UPDATE가 나가지 않는다 (#280)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Immutable
@Entity
@Table(name = "p_inventory_logs")
public class InventoryLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "inventory_log_id")
    private UUID id;

    // 재고와 로그는 별도 애그리거트지만, 허브·경로(#268)와 달리 ID가 아니라 객체로 참조한다 (#280)
    // 로그 목록 응답(허브·상품)과 검색 조건(재고 ID, 허브 ID)이 재고와 함께 조회해야 만들어지고, 같은 서비스·DB에서 항상 함께 읽기 때문이다
    // 대신 로그를 통해 재고를 바꾸지 않는다: 영속성 전이가 없고, 가리키는 재고도 바꿀 수 없다(updatable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id", nullable = false, updatable = false)
    private Inventory inventory;

    // 주문 → order-service p_orders.order_id. 입고(INBOUND)·관리자 조정(ADJUST_*)이면 null
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryChangeType changeType;

    // 변화량 (0 이상, 방향은 changeType으로 구분)
    // 차감 기록 없이 온 복원은 재고를 바꾸지 않고 "복원 요청이 왔었다"는 표시로 0을 남긴다 (늦게 도착한 차감을 막기 위해)
    @Column(nullable = false)
    private Integer quantity;

    // 로그는 아래 정적 팩토리로만 만든다. 변경 유형·orderId·수량을 상황별로 정해 잘못된 조합(주문 없는 차감 등)이 생기지 않게 한다
    // 엔티티 불변식. 팩토리가 만드는 값만 들어오므로 정상 흐름에서는 걸리지 않는다 (걸리면 코드 버그 → 500)
    @Builder(access = AccessLevel.PRIVATE)
    private InventoryLog(Inventory inventory, UUID orderId, InventoryChangeType changeType, Integer quantity) {
        this.inventory = Objects.requireNonNull(inventory, "로그의 재고는 필수입니다.");
        this.orderId = orderId;
        this.changeType = Objects.requireNonNull(changeType, "로그의 변경 유형은 필수입니다.");
        this.quantity = Objects.requireNonNull(quantity, "로그의 수량은 필수입니다.");
        if (quantity < 0) {
            throw new IllegalArgumentException("로그 수량은 0 이상이어야 합니다.");
        }
    }

    // 관리자 입고 (주문 없음)
    public static InventoryLog inbound(Inventory inventory, int quantity) {
        return builder()
                .inventory(inventory)
                .changeType(InventoryChangeType.INBOUND)
                .quantity(quantity)
                .build();
    }

    // 주문 재고 차감. (orderId, DECREASE)가 멱등성 키라 주문 ID가 필수다
    public static InventoryLog decrease(Inventory inventory, UUID orderId, int quantity) {
        return builder()
                .inventory(inventory)
                .orderId(Objects.requireNonNull(orderId, "주문 차감 로그는 주문 ID가 필수입니다."))
                .changeType(InventoryChangeType.DECREASE)
                .quantity(quantity)
                .build();
    }

    // 주문 재고 복원. 차감 기록 없이 온 복원은 수량 0으로 남긴다 (늦게 도착한 차감을 막는 표시)
    public static InventoryLog restore(Inventory inventory, UUID orderId, int quantity) {
        return builder()
                .inventory(inventory)
                .orderId(Objects.requireNonNull(orderId, "주문 복원 로그는 주문 ID가 필수입니다."))
                .changeType(InventoryChangeType.RESTORE)
                .quantity(quantity)
                .build();
    }

    // 관리자 수량 조정. 조회한 수량 → 조정한 수량의 방향으로 변경 유형을, 차이로 변화량을 정한다
    // 같은 수량 조정은 DTO 검증(isQuantityChanged)에서 400으로 막혀 여기까지 오지 않는다
    public static InventoryLog adjust(Inventory inventory, int expectedQuantity, int quantity) {
        if (expectedQuantity == quantity) {
            throw new IllegalArgumentException("조정 전후 수량이 같으면 조정 로그를 만들 수 없습니다.");
        }
        InventoryChangeType changeType = quantity > expectedQuantity
                ? InventoryChangeType.ADJUST_INCREASE
                : InventoryChangeType.ADJUST_DECREASE;
        return builder()
                .inventory(inventory)
                .changeType(changeType)
                .quantity(Math.abs(quantity - expectedQuantity))
                .build();
    }

    // 이 로그가 해당 재고의 것인지 (주문 복원 시 차감한 재고와 복원 요청의 재고가 같은지 확인)
    public boolean isFor(Inventory other) {
        return inventory.getId().equals(other.getId());
    }
}
