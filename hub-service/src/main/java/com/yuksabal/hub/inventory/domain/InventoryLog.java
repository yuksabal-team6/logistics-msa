package com.yuksabal.hub.inventory.domain;

import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.util.Objects;
import java.util.UUID;

// 재고 변경 이력. 기록용이자 (order_id, change_type) 멱등성 키라 삭제하지 않는다 (결정 #18).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_inventory_logs")
public class InventoryLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "inventory_log_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id", nullable = false)
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

    // 엔티티 불변식. 서비스가 만드는 값만 들어오므로 정상 흐름에서는 걸리지 않는다 (걸리면 코드 버그 → 500)
    @Builder
    public InventoryLog(Inventory inventory, UUID orderId, InventoryChangeType changeType, Integer quantity) {
        this.inventory = Objects.requireNonNull(inventory, "로그의 재고는 필수입니다.");
        this.orderId = orderId;
        this.changeType = Objects.requireNonNull(changeType, "로그의 변경 유형은 필수입니다.");
        this.quantity = Objects.requireNonNull(quantity, "로그의 수량은 필수입니다.");
        if (quantity < 0) {
            throw new IllegalArgumentException("로그 수량은 0 이상이어야 합니다.");
        }
    }
}
