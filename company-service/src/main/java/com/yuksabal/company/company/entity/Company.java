package com.yuksabal.company.company.entity;

import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "p_companies")
public class Company extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "company_id")
    private UUID id;

    // 관리 허브 → hub-service p_hubs.hub_id
    @Column(nullable = false)
    private UUID hubId;

    // 업체 관리자 → user-service p_users.user_id
    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 200)
    private String companyName;

    // UNIQUE는 부분 유니크 인덱스로 관리 (결정 #15)
    @Column(length = 50)
    private String businessNumber;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(length = 30)
    private String contactPhone;

    @Builder
    public Company(UUID hubId, UUID ownerId, String companyName, String businessNumber, String address,
                   String contactPhone) {
        this.hubId = hubId;
        this.ownerId = ownerId;
        this.companyName = companyName;
        this.businessNumber = businessNumber;
        this.address = address;
        this.contactPhone = contactPhone;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
