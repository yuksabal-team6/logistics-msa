package com.yuksabal.company.product.entity;

import com.yuksabal.common.entity.BaseEntity;
import com.yuksabal.company.company.entity.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_products")
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "product_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false, length = 200)
    private String productName;

    @Column(columnDefinition = "TEXT")
    private String description;

    // 원 단위 정수
    @Column(nullable = false)
    private Long unitPrice;

    // 판매 가능 여부. 비활성이면 새 주문·입고를 막는다. 활성 상품은 항상 활성 업체에 속한다 (업체 비활성화 시 연쇄 비활성화)
    // ddl-auto update로 컬럼이 추가될 때 기존 상품이 활성으로 채워지도록 DB 기본값을 둔다
    @ColumnDefault("true")
    @Column(nullable = false)
    private boolean isActive = true;

    @Builder
    public Product(Company company, String productName, String description, Long unitPrice) {
        this.company = company;
        this.productName = productName;
        this.description = description;
        this.unitPrice = unitPrice != null ? unitPrice : 0L;
    }

    // 기본 정보 수정. 부분 수정(PATCH)이라 null인 값은 기존 값을 유지한다
    public void updateInfo(String productName, String description, Long unitPrice) {
        if (productName != null) {
            this.productName = productName;
        }
        if (description != null) {
            this.description = description;
        }
        if (unitPrice != null) {
            this.unitPrice = unitPrice;
        }
    }

    // 활성화·비활성화. 권한과 업체 상태 확인은 서비스에서 한다
    public void changeActive(boolean active) {
        this.isActive = active;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
