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

    // 허브 → p_hubs.hub_id. 허브는 별도 애그리거트라 객체가 아니라 ID로 참조한다 (외래 키는 schema.sql)
    @Column(name = "hub_id", nullable = false)
    private UUID hubId;

    // 상품 → company-service p_products.product_id
    @Column(nullable = false)
    private UUID productId;

    // 상품의 업체 → company-service p_companies.company_id
    // 상품의 업체는 바뀌지 않아 재고 생성 시 함께 저장한다 (COMPANY 소유권 확인용)
    @Column(nullable = false)
    private UUID companyId;

    // 수량 규칙은 Quantity가 맡는다 (#282). 컬럼은 기존과 같은 quantity 하나
    // 주문 차감·복원은 재고 행을 잠근 뒤 decrease·increase로 바꾼다 (#273)
    // 입고·관리자 조정은 잠그지 않으므로 조건부 원자 UPDATE로 처리한다 (결정 #14)
    @Getter(AccessLevel.NONE)
    @Embedded
    private Quantity quantity;

    // 엔티티 불변식. DTO 검증·DB 제약 뒤의 마지막 방어선이라 정상 요청으로는 걸리지 않는다 (걸리면 코드 버그 → 500)
    @Builder
    public Inventory(UUID hubId, UUID productId, UUID companyId, Integer quantity) {
        this.hubId = Objects.requireNonNull(hubId, "재고의 허브는 필수입니다.");
        this.productId = Objects.requireNonNull(productId, "재고의 상품은 필수입니다.");
        this.companyId = Objects.requireNonNull(companyId, "재고의 업체는 필수입니다.");
        this.quantity = new Quantity(quantity != null ? quantity : 0);
    }

    // 입고로 처음 생기는 재고. 상품의 업체도 함께 저장한다 (업체는 바뀌지 않아, COMPANY 소유권 확인에 쓴다)
    // 입고 규칙(활성, 관리 허브)은 ProductInfo.checkInboundTo로 먼저 확인한다
    public static Inventory create(UUID hubId, ProductInfo product, int quantity) {
        return builder()
                .hubId(hubId)
                .productId(product.productId())
                .companyId(product.companyId())
                .quantity(quantity)
                .build();
    }

    // 응답·로그는 숫자로 쓰므로 수량은 숫자로 내보낸다
    public int getQuantity() {
        return quantity.value();
    }

    public boolean hasStock() {
        return quantity.isPositive();
    }

    // 주문 재고 차감. 남은 수량이 부족하면 409 (details에 현재 수량)
    // 수량을 읽고 쓰는 사이에 다른 요청이 끼어들지 않도록 잠금 조회한 재고에서만 부른다
    public void decrease(int amount) {
        this.quantity = quantity.minus(amount);
    }

    // 주문 재고 복원. 차감과 같이 잠금 조회한 재고에서만 부른다
    public void increase(int amount) {
        this.quantity = quantity.plus(amount);
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
