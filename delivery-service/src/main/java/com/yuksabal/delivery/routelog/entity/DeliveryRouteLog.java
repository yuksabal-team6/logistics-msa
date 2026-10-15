package com.yuksabal.delivery.routelog.entity;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.common.entity.BaseEntity;
import com.yuksabal.delivery.global.exception.DeliveryErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// 허브 → 허브 이동 기록. P2P라 배송당 최대 1건, 같은 허브 배송이면 0건 (결정 #12, #16)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_delivery_route_logs")
public class DeliveryRouteLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "route_log_id")
    private UUID id;

    // 배송당 1건이지만 @OneToOne은 일반 UNIQUE 제약을 자동 생성해 soft delete와 충돌하므로 @ManyToOne으로 매핑 (결정 #15)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    // 이동 경로 → hub-service p_hub_routes.hub_route_id
    @Column(nullable = false)
    private UUID hubRouteId;

    // 허브 → 허브 이동 담당자 → user-service p_users.user_id
    private UUID deliveryManagerId;

    // 생성 시 경로에서 복사
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal expectedDistanceKm;

    @Column(nullable = false)
    private Integer expectedDurationMinutes;

    // 도착 시 기록
    @Column(precision = 10, scale = 2)
    private BigDecimal actualDistanceKm;

    private Integer actualDurationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteStatus routeStatus;

    private LocalDateTime departedAt;

    private LocalDateTime arrivedAt;

    @Builder
    public DeliveryRouteLog(Delivery delivery, UUID hubRouteId, UUID deliveryManagerId,
                            BigDecimal expectedDistanceKm, Integer expectedDurationMinutes) {
        this.delivery = delivery;
        this.hubRouteId = hubRouteId;
        this.deliveryManagerId = deliveryManagerId;
        this.expectedDistanceKm = expectedDistanceKm;
        this.expectedDurationMinutes = expectedDurationMinutes;
        this.routeStatus = RouteStatus.READY;
    }

    // 허브 출발 전(READY)에만 취소할 수 있다 (결정 #19)
    public void cancel() {
        if (routeStatus != RouteStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "허브 출발 후에는 배송 경로 기록을 취소할 수 없습니다.");
        }
        this.routeStatus = RouteStatus.CANCELLED;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
