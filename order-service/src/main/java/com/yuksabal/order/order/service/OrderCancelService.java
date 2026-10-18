package com.yuksabal.order.order.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.dto.DeliveryCancelRequest;
import com.yuksabal.order.order.dto.response.OrderResponse;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;

// 주문 취소 (결정 19). 주문 CANCELLED → 배송 취소 → 재고 복원 순서
// 외부 호출은 트랜잭션 밖, DB 저장만 짧은 트랜잭션 (TransactionTemplate)
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private static final String CANCEL_REASON = "주문 취소 보상 실패 (재고 복원)";

    private final OrderRepository orderRepository;
    private final DeliveryClient deliveryClient;
    private final OrderCompensator orderCompensator;
    private final TransactionTemplate transactionTemplate;

    /**
     * 주문을 취소한다.
     *
     * @param orderId 취소할 주문 ID
     * @param userId  요청한 사용자 ID
     * @param role    요청한 사용자 권한
     * @return 취소된 주문 정보
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse cancelOrder(UUID orderId, UUID userId, UserRole role) {
        log.debug("[주문 취소] 시작 orderId={}, userId={}, role={}", orderId, userId, role);
        // TODO ② 권한: 주문자 본인, 요청 업체 관리자(company 내부 API 2 ownerId), MASTER. 그 외 403

        // 1. 주문 CANCELLED + 보상 PENDING (CONFIRMED가 아니면 409)
        Order order = transactionTemplate.execute(status -> {
            Order found = findOrder(orderId);
            found.cancel();
            return found;
        });
        log.debug("[주문 취소] 1. CANCELLED 저장 orderId={}", orderId);

        // 2. 배송, 경로 기록 취소 (내부 API 9)
        // TODO ④ 배송 취소 실패(불가) 처리: 4xx/5xx면 CONFIRMED + NOT_REQUIRED로 되돌림,
        //  타임아웃이면 CANCELLED 유지 + 보상 FAILED + Slack
        deliveryClient.cancelDelivery(new DeliveryCancelRequest(orderId));
        log.debug("[주문 취소] 2. 배송 취소 성공 orderId={}", orderId);

        // 3. 재고 복원 (내부 API 4) → 보상 COMPLETED, 실패면 보상 FAILED + Slack 수동 처리 (취소는 유지)
        orderCompensator.restoreStock(order, CANCEL_REASON);

        Order cancelled = transactionTemplate.execute(status -> findOrder(orderId));
        log.debug("[주문 취소] 완료 orderId={}, 보상={}", orderId, cancelled.getCompensationStatus());
        return OrderResponse.from(cancelled);
    }

    /**
     * 주문 ID로 주문을 조회한다.
     * 존재하지 않거나 삭제된 주문이면 예외를 발생시킨다.
     *
     * @param orderId 조회할 주문 ID
     * @return 해당 주문 엔티티
     */
    private Order findOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 주문입니다.", "orderId"));
    }
}
