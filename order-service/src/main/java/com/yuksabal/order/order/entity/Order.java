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
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_orders")
public class Order extends BaseEntity implements Persistable<UUID> {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 주문번호를 만들려고 애플리케이션에서 생성 (결정 #17 "orderId 앱에서 생성")
    @Id
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
        this.id = UUID.randomUUID();
        this.requestCompanyId = requestCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.productId = productId;
        this.departureHubId = departureHubId;
        this.arrivalHubId = arrivalHubId;
        this.ordererId = ordererId;
        this.orderNumber = orderNumber != null ? orderNumber : createOrderNumber(this.id, LocalDate.now(KST));
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = (long) quantity * unitPrice;
        this.requestMessage = requestMessage;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.PENDING;
        this.compensationStatus = CompensationStatus.NOT_REQUIRED;
    }

    // 주문번호: ORD-{yyyyMMdd}-{orderId 하이픈 제거 앞 12자리 대문자}, 날짜는 한국 시간

    /**
     * 주문번호를 생성한다.
     * ORD-{yyyyMMdd}-{orderId 하이픈 제거 앞 12자리 대문자}
     *
     * @param id   order uuid
     * @param date 한국 기준 시간
     * @return 생성된 주문번호
     */
    static String createOrderNumber(UUID id, LocalDate date) {
        String idPart = id.toString().replace("-", "").substring(0, 12).toUpperCase();
        return "ORD-" + date.format(ORDER_DATE) + "-" + idPart;
    }

    /**
     * 주문 엔티티가 신규 엔티티인지 판단한다.
     * Spring Data JPA의 save() 호출 시 ID가 미리 할당된
     * 신규 엔티티(저장 안된) 기존 엔티티로 판단하지 않도록 한다.
     * createdAt이 null이면 신규 엔티티로 판단한다.
     *
     * @return 신규 엔티티 여부
     */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }

    // 배송 생성 성공 (결정 #17)
    public void confirm(UUID deliveryId) {
        this.deliveryId = deliveryId;
        this.status = OrderStatus.CONFIRMED;
    }

    /**
     * 주문 상태를 FAILED로 변경하고 보상 상태를 설정한다.
     *
     * @param compensationStatus 설정할 보상 상태
     */
    public void fail(CompensationStatus compensationStatus) {
        this.status = OrderStatus.FAILED;
        this.compensationStatus = compensationStatus;
    }

    /**
     * 주문의 보상 상태를 COMPLETED로 변경한다.
     * 보상 상태가 PENDING인 경우만 가능하다.
     */
    public void completeCompensation() {
        validateCompensationPending();
        this.compensationStatus = CompensationStatus.COMPLETED;
    }

    /**
     * 주문의 보상 상태를 FAILED로 변경한다.
     * 보상 상태가 PENDING인 경우만 가능하다.
     */
    public void failCompensation() {
        validateCompensationPending();
        this.compensationStatus = CompensationStatus.FAILED;
    }

    /**
     * 보상 처리가 진행 중인지 확인한다.
     * 보상 상태가 pending일 때만 보상 결과를 기록할 수 있다.
     */
    private void validateCompensationPending() {
        if (compensationStatus != CompensationStatus.PENDING) {
            throw new BusinessException(
                    OrderErrorCode.INVALID_STATUS_TRANSITION,
                    "보상 진행 중인 주문만 보상 결과를 기록할 수 있습니다.",
                    "orderId");
        }
    }

    /**
     * 주문 상태를 완료로 변경한다.(배송 완료 시)
     * COMPLETED 면 변경 없음(멱등성), CONFIRMED에서만 COMPLETED로 변경 가능하다.
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

    /**
     * 확정된 주문을 취소 상태로 변경한다.
     *
     * <ul>
     *   <li>CONFIRMED 상태에서만 변경 가능하다.</li>
     *   <li>배송 취소와 재고 복원이 남아 있으므로 보상 상태는 PENDING으로 설정한다.</li>
     * </ul>
     */
    public void cancel() {
        if (status != OrderStatus.CONFIRMED) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION,
                    "확정된 주문만 취소할 수 있습니다.", "orderId");
        }
        this.status = OrderStatus.CANCELLED;
        this.compensationStatus = CompensationStatus.PENDING;
    }

    /**
     * 배송 취소 실패 시 상태를 되돌린다.
     * CANCELLED + 보상 PENDING 상태에서만 CONFIRMED + NOT_REQUIRED 로 변경할 수 있다.
     */
    public void revertCancel() {
        if (status != OrderStatus.CANCELLED || compensationStatus != CompensationStatus.PENDING) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS_TRANSITION,
                    "취소 진행 중인 주문만 되돌릴 수 있습니다.", "orderId");
        }
        this.status = OrderStatus.CONFIRMED;
        this.compensationStatus = CompensationStatus.NOT_REQUIRED;
    }

    /**
     * 주문의 삭제 가능 여부를 판단한다.
     *
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

    /**
     * 동일 멱등성 키 재요청 시 기존 주문과 같은 내용인지 확인한다.
     *
     * @param requestCompanyId  현재 요청의 요청 업체
     * @param supplierCompanyId 현재 요청의 공급 업체
     * @param productId         현재 요청의 상품
     * @param quantity          현재 요청의 수량
     * @return 주문 내용이 일치하면 true, 다르면 false
     */
    public boolean isSameRequest(UUID requestCompanyId, UUID supplierCompanyId, UUID productId, Integer quantity) {
        return this.requestCompanyId.equals(requestCompanyId)
                && this.supplierCompanyId.equals(supplierCompanyId)
                && this.productId.equals(productId)
                && this.quantity.equals(quantity);
    }

    public boolean isOrderedBy(UUID userId) {
        return ordererId.equals(userId);
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
