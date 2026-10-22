package com.yuksabal.delivery.delivery.domain;

import com.yuksabal.common.entity.BaseEntity;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.delivery.global.exception.DeliveryErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

// 애그리거트 루트. 경로 기록(DeliveryRouteLog)의 변경은 이 클래스의 메서드로만 한다
// 연관관계는 경로 기록 → 배송 단방향이라, 서비스가 경로 기록을 조회해 넘긴다 (배송당 0~1건)
// 권한 확인은 서비스, 상태 규칙은 여기서 맡는다
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

    // 공급 업체. 지금은 저장만 하고, 업체 기준 조회로 넓힐 때 사용 (order와 함께 변경)
    private UUID supplierCompanyId;

    // 허브 → 업체 배송 담당자 → user-service p_users.user_id
    private UUID deliveryManagerId;

    // 주문자 → user-service p_users.user_id. 조회 권한(주문자 본인)에 쓴다. 이 필드 추가 전 배송은 null
    private UUID ordererId;

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
                    UUID supplierCompanyId, UUID deliveryManagerId, UUID ordererId, String address,
                    String recipientName, String recipientSlackId) {
        this.orderId = orderId;
        this.departureHubId = departureHubId;
        this.destinationHubId = destinationHubId;
        this.destinationCompanyId = destinationCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.deliveryManagerId = deliveryManagerId;
        this.ordererId = ordererId;
        this.address = address;
        this.recipientName = recipientName;
        this.recipientSlackId = recipientSlackId;
        this.deliveryStatus = DeliveryStatus.READY;

    }

    // ordererId가 없는 배송(필드 추가 전)은 누구의 주문도 아니다
    public boolean isOrderedBy(UUID userId) {
        return userId.equals(ordererId);
    }

    public boolean isCancelled() {
        return deliveryStatus == DeliveryStatus.CANCELLED;
    }

    public boolean isCompleted() {
        return deliveryStatus == DeliveryStatus.COMPLETED;
    }

    // 주문 취소·보상(결정 #17, #19). 같은 배송으로 여러 번 불려도 결과가 같다
    // 확인 순서: 이미 취소 → 완료 → 경로 기록 → 배송. 완료를 경로 기록보다 먼저 확인해야 order가 주문을 COMPLETED로 맞춘다 (ORD-22)
    // 배송이 READY여도 경로 기록이 이미 출발했을 수 있어 경로 기록을 배송보다 먼저 확인한다
    public void cancel(Optional<DeliveryRouteLog> routeLog) {
        if (isCancelled()) {
            return;   // 이미 취소됨: 재시도 → 아무것도 바꾸지 않고 성공
        }
        if (isCompleted()) {
            // 출발 후 취소 불가(INVALID_STATUS_TRANSITION)와 구분해, order가 주문을 COMPLETED로 처리한다
            throw new BusinessException(DeliveryErrorCode.DELIVERY_ALREADY_COMPLETED);
        }
        routeLog.ifPresent(found -> {
            checkOwnRouteLog(found);
            found.checkCancelable();
        });
        // 허브 출발 전(READY)에만 취소할 수 있다 (결정 #19)
        if (deliveryStatus != DeliveryStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "허브 출발 후에는 배송을 취소할 수 없습니다.");
        }
        routeLog.ifPresent(DeliveryRouteLog::cancel);
        this.deliveryStatus = DeliveryStatus.CANCELLED;
    }

    // 배송(허브→업체) 구간 상태 변경. IN_TRANSIT·ARRIVED_AT_HUB는 changeRouteStatus(), CANCELLED는 cancel()에서만 바꾼다
    public void changeStatus(DeliveryStatus target) {
        switch (target) {
            case DELIVERING -> startDelivery();
            case COMPLETED -> complete();
            default -> throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "이 API로 변경할 수 없는 상태입니다.");
        }
    }

    // 경로 기록(허브→허브) 상태 변경. 배송 상태도 같이 바꾼다. CANCELLED는 cancel()에서만 바꾼다
    public void changeRouteStatus(DeliveryRouteLog routeLog, RouteStatus target, BigDecimal actualDistanceKm) {
        checkOwnRouteLog(routeLog);
        switch (target) {
            case IN_TRANSIT -> departRoute(routeLog);
            case ARRIVED -> arriveRoute(routeLog, actualDistanceKm);
            default -> throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "이 API로 변경할 수 없는 상태입니다.");
        }
    }

    // 경로 기록 READY → IN_TRANSIT, 배송 READY → IN_TRANSIT. 경로 기록을 먼저 확인한다
    private void departRoute(DeliveryRouteLog routeLog) {
        routeLog.checkDepartable();
        departHub();
        routeLog.depart();
    }

    // 경로 기록 IN_TRANSIT → ARRIVED, 배송 IN_TRANSIT → ARRIVED_AT_HUB
    // 경로 기록 409 → 거리 400 → 배송 409 순서. 거리는 배송 상태를 바꾸기 전에 검증한다
    private void arriveRoute(DeliveryRouteLog routeLog, BigDecimal actualDistanceKm) {
        routeLog.checkArrivable(actualDistanceKm);
        arriveAtHub();
        routeLog.arrive(actualDistanceKm);
    }

    // 경로 기록 출발 연동 (외부 API로는 바꿀 수 없음)
    private void departHub() {
        if (deliveryStatus != DeliveryStatus.READY) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "READY 상태의 배송만 허브에서 출발할 수 있습니다.");
        }
        this.deliveryStatus = DeliveryStatus.IN_TRANSIT;
    }

    // 경로 기록 도착 연동 (외부 API로는 바꿀 수 없음)
    private void arriveAtHub() {
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

    // 배송(허브→업체) 담당자의 구간은 DELIVERING부터 시작한다. 출발 전(READY·IN_TRANSIT·ARRIVED_AT_HUB)에만 지정·재지정할 수 있다
    // 허용 상태만 나열해 새 상태가 추가돼도 기본은 지정 불가. user 호출 전 1차 확인에도 쓴다 (지정할 수 없으면 user를 부르지 않도록)
    public void checkManagerAssignable() {
        boolean assignable = switch (deliveryStatus) {
            case READY, IN_TRANSIT, ARRIVED_AT_HUB -> true;
            default -> false;
        };
        if (!assignable) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "업체로 출발했거나 끝난 배송은 담당자를 지정할 수 없습니다.");
        }
    }

    // 배송(허브→업체) 담당자 지정. 회원 검증(활성 DELIVERY)은 서비스가 user 호출로 먼저 한다
    public void assignManager(UUID deliveryManagerId) {
        checkManagerAssignable();
        this.deliveryManagerId = deliveryManagerId;
    }

    // 경로 기록(허브→허브) 담당자 지정. 지정 가능 상태는 경로 기록이 정한다. 회원 검증은 서비스가 user 호출로 먼저 한다
    public void assignRouteManager(DeliveryRouteLog routeLog, UUID deliveryManagerId) {
        checkOwnRouteLog(routeLog);
        routeLog.assignManager(deliveryManagerId);
    }

    private boolean isSameHub() {
        return departureHubId.equals(destinationHubId);
    }

    // 끝난 배송(COMPLETED·CANCELLED)만 삭제할 수 있다. 진행 중 기준은 DeliveryStatus가 정한다
    // 배송이 끝났으면 경로 기록은 상태와 상관없이 함께 Soft Delete한다 (어긋난 데이터를 MASTER가 정리하는 수단)
    public void delete(Optional<DeliveryRouteLog> routeLog, UUID deletedBy) {
        if (deliveryStatus.isInProgress()) {
            throw new BusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION,
                    "진행 중인 배송은 삭제할 수 없습니다.");
        }
        routeLog.ifPresent(found -> {
            checkOwnRouteLog(found);
            found.delete(deletedBy);
        });
        markDeleted(deletedBy);
    }

    // 서비스가 다른 배송의 경로 기록을 404로 막지만, 그걸 지나 들어온 경우를 막는 안전장치. 응답은 서비스 404와 같다
    private void checkOwnRouteLog(DeliveryRouteLog routeLog) {
        if (!routeLog.belongsTo(id)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "존재하지 않거나 삭제된 배송 경로 기록입니다.", "routeLogId");
        }
    }
}
