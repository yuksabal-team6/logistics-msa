package com.yuksabal.order.order.entity;

import com.yuksabal.common.entity.BaseEntity;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.order.global.exception.OrderErrorCode;
import jakarta.persistence.*;
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
@Table(name = "p_orders")
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id")
    private UUID id;

    // 요청(수령) 업체 → company-service p_companies.company_id
    @Column(nullable = false)
    private UUID requestCompanyId;

    // 공급 업체 → company-service p_companies.company_id
    @Column(nullable = false)
    private UUID supplierCompanyId;

    // 상품 → company-service p_products.product_id
    @Column(nullable = false)
    private UUID productId;

    // 출발/도착 허브 → hub-service p_hubs.hub_id. 같을 수 있음 (결정 #16)
    @Column(nullable = false)
    private UUID departureHubId;

    @Column(nullable = false)
    private UUID arrivalHubId;

    // 주문자 → user-service p_users.user_id
    @Column(nullable = false)
    private UUID ordererId;

    // 배송 생성 후 채움 → delivery-service p_deliveries.delivery_id
    private UUID deliveryId;

    // UNIQUE는 부분 유니크 인덱스로 관리 (결정 #15)
    @Column(nullable = false, length = 50)
    private String orderNumber;

    @Column(nullable = false)
    private Integer quantity;

    // 주문 시점 단가 (원 단위 정수)
    @Column(nullable = false)
    private Long unitPrice;

    // ERD상 DB 자동 계산 컬럼. ddl-auto: update 단계에서는 엔티티가 계산해 저장
    private Long totalAmount;

    @Column(columnDefinition = "TEXT")
    private String requestMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompensationStatus compensationStatus;

    // Idempotency-Key 헤더 값 (결정 #18). UNIQUE는 부분 유니크 인덱스로 관리
    @Column(length = 100)
    private String idempotencyKey;

    @Builder
    public Order(UUID requestCompanyId, UUID supplierCompanyId, UUID productId, UUID departureHubId,
                 UUID arrivalHubId, UUID ordererId, String orderNumber, Integer quantity, Long unitPrice,
                 String requestMessage, String idempotencyKey) {
        this.requestCompanyId = requestCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.productId = productId;
        this.departureHubId = departureHubId;
        this.arrivalHubId = arrivalHubId;
        this.ordererId = ordererId;
        this.orderNumber = orderNumber;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = (long) quantity * unitPrice;
        this.requestMessage = requestMessage;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.PENDING;
        this.compensationStatus = CompensationStatus.NOT_REQUIRED;
    }

    // 배송 생성 성공 (결정 #17 ④)
    public void confirm(UUID deliveryId) {
        this.deliveryId = deliveryId;
        this.status = OrderStatus.CONFIRMED;
    }

    // 생성 실패. 재고 부족이면 NOT_REQUIRED, 보상이 필요하면 PENDING (결정 #21)
    public void fail(CompensationStatus compensationStatus) {
        this.status = OrderStatus.FAILED;
        this.compensationStatus = compensationStatus;
    }

    // 보상 결과 기록: COMPLETED 또는 FAILED(수동 처리 대상)
    public void changeCompensationStatus(CompensationStatus compensationStatus) {
        this.compensationStatus = compensationStatus;
    }

    /**
     * 주문을 완료한다.(배송 완료 시)
     * COMPLETED 면 변경 없음, CONFIRMED에서만 COMPLETED로 변경 가능하다.
     */
    public void complete() {
        if (status == OrderStatus.COMPLETED) {
            return;
        }
        if (status != OrderStatus.CONFIRMED) {
            throw new BusinessException(
                    OrderErrorCode.INVALID_STATUS_TRANSITION,
                    "배송 중인 주문만 완료 처리할 수 있습니다.",
                    "orderId");
        }
        this.status = OrderStatus.COMPLETED;
    }

    // TODO 확인: 취소(CANCELLED) 전이 규칙은 주문 취소 구현 때 추가 (결정 #19)

    /**
     * 주문의 삭제 가능 여부를 판단한다.
     * 완료된 주문 또는 보상 처리가 끝난 실패/취소 주문만 삭제할 수 있다.
     * COMPLETED, 또는 보상이 끝났거나 필요 없는 FAILED/CANCELLED
     *
     * @return 삭제 가능하면 true, 그렇지 않으면 false
     */
    public boolean isDeletable() {
        if (status == OrderStatus.COMPLETED) {
            return true;
        }
        boolean compensationDone = compensationStatus == CompensationStatus.NOT_REQUIRED
                || compensationStatus == CompensationStatus.COMPLETED;
        return (status == OrderStatus.FAILED || status == OrderStatus.CANCELLED) && compensationDone;
    }

    public boolean isOrderedBy(UUID userId) {
        return ordererId.equals(userId);
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
