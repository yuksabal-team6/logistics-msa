package com.yuksabal.hub.inventory.domain;

import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

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

    // 주문 → order-service p_orders.order_id. 입고(INBOUND)면 null
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryChangeType changeType;

    @Column(nullable = false)
    private Integer quantity;

    @Builder
    public InventoryLog(Inventory inventory, UUID orderId, InventoryChangeType changeType, Integer quantity) {
        this.inventory = inventory;
        this.orderId = orderId;
        this.changeType = changeType;
        this.quantity = quantity;
    }
}
