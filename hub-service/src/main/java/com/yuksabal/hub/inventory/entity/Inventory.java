package com.yuksabal.hub.inventory.entity;

import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_inventories")
public class Inventory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "inventory_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hub_id", nullable = false)
    private Hub hub;

    // 상품 → company-service p_products.product_id
    @Column(nullable = false)
    private UUID productId;

    // 상품의 업체 → company-service p_companies.company_id
    // 상품의 업체는 바뀌지 않아 재고 생성 시 함께 저장한다 (COMPANY 소유권 확인용)
    @Column(nullable = false)
    private UUID companyId;

    // 차감·복원·입고는 조건부 원자 UPDATE로 처리한다 (결정 #14)
    @Column(nullable = false)
    private Integer quantity;

    @Builder
    public Inventory(Hub hub, UUID productId, UUID companyId, Integer quantity) {
        this.hub = hub;
        this.productId = productId;
        this.companyId = companyId;
        this.quantity = quantity != null ? quantity : 0;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
