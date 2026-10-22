package com.yuksabal.delivery.delivery.domain;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
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

    // 아래 변경 메서드는 package-private이다. 경로 기록 변경은 애그리거트 루트인 Delivery를 통해서만 한다
    // Delivery가 경로 기록 확인 → 배송 전이 → 경로 기록 변경 순서로 불러, 실패하면 둘 다 바뀌지 않는다

    // 허브 출발 전(READY)에만 취소할 수 있다 (결정 #19)
    void checkCancelable() {
        if (routeStatus != RouteStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "허브 출발 후에는 배송 경로 기록을 취소할 수 없습니다.");
        }
    }

    void cancel() {
        checkCancelable();
        this.routeStatus = RouteStatus.CANCELLED;
    }

    void checkDepartable() {
        if (routeStatus != RouteStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "READY 상태에서만 허브를 출발할 수 있습니다.");
        }
    }

    // READY → IN_TRANSIT. 배송 상태는 Delivery가 바꾼다
    void depart() {
        checkDepartable();
        this.routeStatus = RouteStatus.IN_TRANSIT;
        this.departedAt = LocalDateTime.now();
    }

    // 409(전이 불가)를 400(거리 누락)보다 먼저 확인한다
    void checkArrivable(BigDecimal actualDistanceKm) {
        if (routeStatus != RouteStatus.IN_TRANSIT) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "이동 중(IN_TRANSIT)인 경로 기록만 도착 처리할 수 있습니다.");
        }
        if (actualDistanceKm == null || actualDistanceKm.signum() <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "도착 시 실제 거리(km)는 0보다 커야 합니다.", "actualDistanceKm");
        }
    }

    // IN_TRANSIT → ARRIVED. 배송 상태는 Delivery가 바꾼다
    void arrive(BigDecimal actualDistanceKm) {
        checkArrivable(actualDistanceKm);
        this.routeStatus = RouteStatus.ARRIVED;
        this.arrivedAt = LocalDateTime.now();
        this.actualDistanceKm = actualDistanceKm;
        // 실제 소요 시간은 입력받지 않고 출발·도착 시각 차이(분)로 계산한다
        this.actualDurationMinutes = (int) Duration.between(departedAt, arrivedAt).toMinutes();
    }

    // 허브 간 이동 담당자의 구간은 IN_TRANSIT부터 시작한다. 출발 전(READY)에만 지정·재지정할 수 있다
    // 허용 상태만 확인해 새 상태가 추가돼도 기본은 지정 불가. user 호출 전 1차 확인에도 쓴다 (지정할 수 없으면 user를 부르지 않도록)
    public void checkManagerAssignable() {
        if (routeStatus != RouteStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "허브를 출발했거나 끝난 경로 기록은 담당자를 지정할 수 없습니다.");
        }
    }

    // 허브 간 이동 담당자 지정. 회원 검증(활성 DELIVERY)은 서비스가 user 호출로 먼저 한다
    void assignManager(UUID deliveryManagerId) {
        checkManagerAssignable();
        this.deliveryManagerId = deliveryManagerId;
    }

    // 삭제 가능 여부는 배송 상태로만 정한다 (Delivery.delete). 어긋난 데이터도 배송 삭제로 정리할 수 있도록 경로 기록 상태는 보지 않는다
    void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }

    // 다른 배송의 경로 기록인지 확인한다. 서비스의 findRouteLog 필터와 같은 id 비교 (프록시의 getId()는 추가 조회를 하지 않는다)
    boolean belongsTo(UUID deliveryId) {
        return deliveryId != null && deliveryId.equals(delivery.getId());
    }
}
