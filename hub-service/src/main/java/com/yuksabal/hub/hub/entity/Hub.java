package com.yuksabal.hub.hub.entity;

import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

// 허브 17개는 고정 초기 데이터라 조회만 한다. 삭제 기능이 없으므로 delete()를 두지 않는다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_hubs")
public class Hub extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "hub_id")
    private UUID id;

    // UNIQUE는 부분 유니크 인덱스로 관리 (결정 #15)
    @Column(nullable = false, length = 200)
    private String hubName;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Builder
    public Hub(String hubName, String address, BigDecimal latitude, BigDecimal longitude) {
        this.hubName = hubName;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
