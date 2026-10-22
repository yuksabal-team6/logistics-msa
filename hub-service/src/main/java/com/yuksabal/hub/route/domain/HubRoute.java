package com.yuksabal.hub.route.domain;

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

    // 출발·도착 허브 → p_hubs.hub_id. 허브는 별도 애그리거트라 객체가 아니라 ID로 참조한다 (외래 키는 schema.sql)
    @Column(name = "departure_hub_id", nullable = false)
    private UUID departureHubId;

    @Column(name = "arrival_hub_id", nullable = false)
    private UUID arrivalHubId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @Column(nullable = false)
    private Integer durationMinutes;

    @Builder
    public HubRoute(UUID departureHubId, UUID arrivalHubId, BigDecimal distanceKm, Integer durationMinutes) {
        this.departureHubId = departureHubId;
        this.arrivalHubId = arrivalHubId;
        this.distanceKm = distanceKm;
        this.durationMinutes = durationMinutes;
    }
}
