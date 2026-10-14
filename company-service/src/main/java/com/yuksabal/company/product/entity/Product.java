package com.yuksabal.company.product.entity;

import com.yuksabal.company.company.entity.Company;
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

    @Builder
    public Product(Company company, String productName, String description, Long unitPrice) {
        this.company = company;
        this.productName = productName;
        this.description = description;
        this.unitPrice = unitPrice != null ? unitPrice : 0L;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
