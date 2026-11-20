package com.yuksabal.order.order.event;

import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Kafka order.events 토픽으로 발행하는 주문 이벤트.
 * <p>
 * 알림에 필요한 데이터를 포함하여 Consumer가 주문 DB를
 * 재조회하지 않고 이벤트만으로 처리할 수 있도록 한다.
 * 이벤트의 주문 정보는 생성 시점의 상태를 나타낸다.
 *
 * @param eventType          이벤트 타입
 * @param orderId            주문 ID
 * @param orderNumber        주문번호
 * @param status             이벤트 생성 시점의 주문 상태
 * @param compensationStatus 이벤트 생성 시점의 보상 상태
 * @param quantity           주문 수량
 * @param unitPrice          상품 단가
 * @param totalAmount        주문 총금액
 * @param requestMessage     주문 요청사항
 * @param productName        상품명
 * @param supplierName       공급 업체명
 * @param requesterName      요청 업체명
 * @param ordererSlackId     주문자의 slack id
 * @param reason             수동 처리 또는 확인이 필요한 사유
 * @param occurredAt         이벤트 생성 시각
 */
public record OrderEvent(
        OrderEventType eventType,
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        CompensationStatus compensationStatus,
        Integer quantity,
        Long unitPrice,
        Long totalAmount,
        String requestMessage,
        // 주문 확정일 때만 (company 조회 결과, 주문자 slackId)
        String productName,
        String supplierName,
        String requesterName,
        String ordererSlackId,
        // 수동 처리, 확인 필요일 때만
        String reason,
        LocalDateTime occurredAt
) {

    // Kafka 주문 이벤트 토픽 이름
    public static final String TOPIC = "order.events";

    // 이벤트 생성 시각에 사용할 한국 시간대
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 주문 확정 시 ORDER_CONFIRMED 이벤트를 생성한다.
     *
     * @param order          주문 엔티티
     * @param productName    상품명
     * @param supplierName   공급 업체명
     * @param requesterName  요청 업체명
     * @param ordererSlackId 주문자 slack id
     * @return 생성된 주문 확정 이벤트
     */
    public static OrderEvent orderConfirmed(Order order, String productName, String supplierName, String requesterName,
                                            String ordererSlackId) {
        return of(OrderEventType.ORDER_CONFIRMED, order, productName, supplierName, requesterName, ordererSlackId, null);
    }

    /**
     * 보상 실패 등 수동 처리가 필요할 때 MANUAL_ACTION_REQUIRED 이벤트를 생성한다.
     *
     * @param order  이벤트에 들어갈 주문 엔티티
     * @param reason 수동 처리가 필요한 사유
     * @return 생성된 수동 처리 요청 이벤트
     */
    public static OrderEvent manualActionRequired(Order order, String reason) {
        return of(OrderEventType.MANUAL_ACTION_REQUIRED, order, null, null, null, null, reason);
    }

    /**
     * 예상하지 못한 오류로 확인이 필요할 때 CHECK_NEEDED 이벤트를 생성한다.
     *
     * @param order  이벤트에 들어갈 주문 엔티티
     * @param reason 확인이 필요한 사유
     * @return 생성된 확인 요청 이벤트
     */
    public static OrderEvent checkNeeded(Order order, String reason) {
        return of(OrderEventType.CHECK_NEEDED, order, null, null, null, null, reason);
    }

    // 공통 이벤트 생성 메서드 (중복 최소화)
    private static OrderEvent of(OrderEventType type, Order order, String productName, String supplierName,
                                 String requesterName, String ordererSlackId, String reason) {
        return new OrderEvent(type, order.getId(), order.getOrderNumber(), order.getStatus(), order.getCompensationStatus(),
                order.getQuantity(), order.getUnitPrice(), order.getTotalAmount(), order.getRequestMessage(),
                productName, supplierName, requesterName, ordererSlackId, reason, LocalDateTime.now(KST));
    }
}
