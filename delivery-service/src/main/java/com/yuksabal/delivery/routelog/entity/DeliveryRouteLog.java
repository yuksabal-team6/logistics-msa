package com.yuksabal.delivery.routelog.entity;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
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
import java.time.Duration;
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

    // 낙관적 락. 기존 행은 null이라 schema.sql에서 0으로 채운다
    @Version
    private Long version;

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

    // 경로 기록(허브→허브) 상태 변경. 배송 상태도 같이 바꾼다. CANCELLED는 cancel()에서만 바꾼다
    public void changeStatus(RouteStatus target, BigDecimal actualDistanceKm) {
        switch (target) {
            case IN_TRANSIT -> depart();
            case ARRIVED -> arrive(actualDistanceKm);
            default -> throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "이 API로 변경할 수 없는 상태입니다.");
        }
    }

    // READY → IN_TRANSIT, 배송은 READY → IN_TRANSIT
    private void depart() {
        if (routeStatus != RouteStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "READY 상태에서만 허브를 출발할 수 있습니다.");
        }
        delivery.departHub();
        this.routeStatus = RouteStatus.IN_TRANSIT;
        this.departedAt = LocalDateTime.now();
    }

    // IN_TRANSIT → ARRIVED, 배송은 IN_TRANSIT → ARRIVED_AT_HUB
    // 409(전이 불가)를 400(거리 누락)보다 먼저 확인한다. 400이면 배송 상태가 이미 바뀌었지만 트랜잭션 롤백으로 반영되지 않는다
    private void arrive(BigDecimal actualDistanceKm) {
        if (routeStatus != RouteStatus.IN_TRANSIT) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "이동 중(IN_TRANSIT)인 경로 기록만 도착 처리할 수 있습니다.");
        }
        delivery.arriveAtHub();
        if (actualDistanceKm == null || actualDistanceKm.signum() <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "도착 시 실제 거리(km)는 0보다 커야 합니다.", "actualDistanceKm");
        }
        this.routeStatus = RouteStatus.ARRIVED;
        this.arrivedAt = LocalDateTime.now();
        this.actualDistanceKm = actualDistanceKm;
        // 실제 소요 시간은 입력받지 않고 출발·도착 시각 차이(분)로 계산한다
        this.actualDurationMinutes = (int) Duration.between(departedAt, arrivedAt).toMinutes();
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
