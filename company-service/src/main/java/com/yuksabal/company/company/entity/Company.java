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
import org.hibernate.annotations.ColumnDefault;
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

    // 거래 가능 여부. 비활성이면 새 주문·입고·상품 등록을 막고, 진행 중인 주문·배송은 그대로 진행된다
    // ddl-auto update로 컬럼이 추가될 때 기존 업체가 활성으로 채워지도록 DB 기본값을 둔다
    @ColumnDefault("true")
    @Column(nullable = false)
    private boolean isActive = true;

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

    // 기본 정보 수정. 부분 수정(PATCH)이라 null인 값은 기존 값을 유지한다
    public void updateInfo(String companyName, String address, String contactPhone, String businessNumber) {
        if (companyName != null) {
            this.companyName = companyName;
        }
        if (address != null) {
            this.address = address;
        }
        if (contactPhone != null) {
            this.contactPhone = contactPhone;
        }
        if (businessNumber != null) {
            this.businessNumber = businessNumber;
        }
    }

    // 관리 허브 이전. 권한(MASTER만)과 허브 존재 확인은 서비스에서 한다
    public void changeHub(UUID hubId) {
        this.hubId = hubId;
    }

    // 업체 관리자 교체. 권한(MASTER만)과 새 관리자 소속 확인은 서비스에서 한다
    public void changeOwner(UUID ownerId) {
        this.ownerId = ownerId;
    }

    // 활성화·비활성화. 권한(MASTER만)과 소속 상품 연쇄 비활성화는 서비스에서 한다
    public void changeActive(boolean active) {
        this.isActive = active;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
