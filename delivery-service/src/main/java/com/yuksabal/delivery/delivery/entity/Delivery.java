package com.yuksabal.delivery.delivery.entity;

import com.yuksabal.common.entity.BaseEntity;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.delivery.global.exception.DeliveryErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_deliveries")
public class Delivery extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "delivery_id")
    private UUID id;

    // 주문 → order-service p_orders.order_id. 주문 하나에 배송 하나, UNIQUE는 부분 유니크 인덱스로 관리 (결정 #15)
    @Column(nullable = false)
    private UUID orderId;

    // 출발·목적지 허브 → hub-service p_hubs.hub_id
    @Column(nullable = false)
    private UUID departureHubId;

    @Column(nullable = false)
    private UUID destinationHubId;

    // 도착(수령) 업체 → company-service p_companies.company_id
    @Column(nullable = false)
    private UUID destinationCompanyId;

    // 허브 → 업체 배송 담당자 → user-service p_users.user_id
    private UUID deliveryManagerId;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(nullable = false, length = 100)
    private String recipientName;

    @Column(length = 100)
    private String recipientSlackId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus deliveryStatus;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    // 낙관적 락. 기존 행은 null이라 schema.sql에서 0으로 채운다
    @Version
    private Long version;

    @Builder
    public Delivery(UUID orderId, UUID departureHubId, UUID destinationHubId, UUID destinationCompanyId,
                    UUID deliveryManagerId, String address, String recipientName, String recipientSlackId) {
        this.orderId = orderId;
        this.departureHubId = departureHubId;
        this.destinationHubId = destinationHubId;
        this.destinationCompanyId = destinationCompanyId;
        this.deliveryManagerId = deliveryManagerId;
        this.address = address;
        this.recipientName = recipientName;
        this.recipientSlackId = recipientSlackId;
        this.deliveryStatus = DeliveryStatus.READY;

    }

    public boolean isCancelled() {
        return deliveryStatus == DeliveryStatus.CANCELLED;
    }

    // 허브 출발 전(READY)에만 취소할 수 있다 (결정 #19)
    public void cancel() {
        if (deliveryStatus != DeliveryStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "허브 출발 후에는 배송을 취소할 수 없습니다.");
        }
        this.deliveryStatus = DeliveryStatus.CANCELLED;
    }

    // 배송(허브→업체) 구간 상태 변경. IN_TRANSIT·ARRIVED_AT_HUB는 경로 기록 연동, CANCELLED는 cancel()에서만 바꾼다
    public void changeStatus(DeliveryStatus target) {
        switch (target) {
            case DELIVERING -> startDelivery();
            case COMPLETED -> complete();
            default -> throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "이 API로 변경할 수 없는 상태입니다.");
        }
    }

    // 경로 기록 READY → IN_TRANSIT 연동. DeliveryRouteLog 상태 변경에서만 호출한다 (외부 API로는 바꿀 수 없음)
    public void departHub() {
        if (deliveryStatus != DeliveryStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "READY 상태의 배송만 허브에서 출발할 수 있습니다.");
        }
        this.deliveryStatus = DeliveryStatus.IN_TRANSIT;
    }

    // 경로 기록 IN_TRANSIT → ARRIVED 연동. DeliveryRouteLog 상태 변경에서만 호출한다 (외부 API로는 바꿀 수 없음)
    public void arriveAtHub() {
        if (deliveryStatus != DeliveryStatus.IN_TRANSIT) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "이동 중(IN_TRANSIT)인 배송만 허브에 도착할 수 있습니다.");
        }
        this.deliveryStatus = DeliveryStatus.ARRIVED_AT_HUB;
    }

    // 같은 허브: READY → DELIVERING, 다른 허브: ARRIVED_AT_HUB → DELIVERING (결정 #16)
    private void startDelivery() {
        DeliveryStatus required = isSameHub() ? DeliveryStatus.READY : DeliveryStatus.ARRIVED_AT_HUB;
        if (deliveryStatus != required) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    required + " 상태에서만 배송을 시작할 수 있습니다.");
        }
        this.deliveryStatus = DeliveryStatus.DELIVERING;
        this.startedAt = LocalDateTime.now();
    }

    // DELIVERING → COMPLETED
    private void complete() {
        if (deliveryStatus != DeliveryStatus.DELIVERING) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "배송 중(DELIVERING)인 배송만 완료할 수 있습니다.");
        }
        this.deliveryStatus = DeliveryStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    private boolean isSameHub() {
        return departureHubId.equals(destinationHubId);
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
