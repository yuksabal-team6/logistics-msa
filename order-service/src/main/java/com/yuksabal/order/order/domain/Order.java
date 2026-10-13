package com.yuksabal.order.order.domain;

import com.yuksabal.common.entity.BaseEntity;
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
    private UUID requesterCompanyId;

    // 공급 업체 → company-service p_companies.company_id
    @Column(nullable = false)
    private UUID supplierCompanyId;

    // 상품 → company-service p_products.product_id
    @Column(nullable = false)
    private UUID productId;

    // 출발·도착 허브 → hub-service p_hubs.hub_id. 같을 수 있음 (결정 #16)
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

    // ERD상 DB 자동 계산 컬럼. ddl-auto: update 단계에서는 엔티티가 계산해 저장한다
    private Long totalAmount;

    @Column(columnDefinition = "TEXT")
    private String requestNote;

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
    public Order(UUID requesterCompanyId, UUID supplierCompanyId, UUID productId, UUID departureHubId,
                 UUID arrivalHubId, UUID ordererId, String orderNumber, Integer quantity, Long unitPrice,
                 String requestNote, String idempotencyKey) {
        this.requesterCompanyId = requesterCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.productId = productId;
        this.departureHubId = departureHubId;
        this.arrivalHubId = arrivalHubId;
        this.ordererId = ordererId;
        this.orderNumber = orderNumber;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = (long) quantity * unitPrice;
        this.requestNote = requestNote;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.PENDING;
        this.compensationStatus = CompensationStatus.NOT_REQUIRED;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
