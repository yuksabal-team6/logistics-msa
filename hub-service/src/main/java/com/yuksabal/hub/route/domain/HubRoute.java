package com.yuksabal.hub.route.domain;

import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

// 허브 간 P2P 경로. 고정 초기 데이터라 조회만 하므로 delete()를 두지 않는다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_hub_routes")
public class HubRoute extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "hub_route_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "departure_hub_id", nullable = false)
    private Hub departureHub;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arrival_hub_id", nullable = false)
    private Hub arrivalHub;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @Column(nullable = false)
    private Integer durationMinutes;

    @Builder
    public HubRoute(Hub departureHub, Hub arrivalHub, BigDecimal distanceKm, Integer durationMinutes) {
        this.departureHub = departureHub;
        this.arrivalHub = arrivalHub;
        this.distanceKm = distanceKm;
        this.durationMinutes = durationMinutes;
    }
}
