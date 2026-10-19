package com.yuksabal.order.order.service;

import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.dto.DeliveryCancelRequest;
import com.yuksabal.order.order.client.dto.StockRequest;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 주문 보상 처리를 담당한다.
 * <ul>
 *     <li>주문 생성 실패 / 주문 취소 시 재고 복원과 배송 취소를 수행한다.</li>
 *     <li>보상 결과: 성공 → 보상 COMPLETED / 실패 → 보상 FAILED + Slack 수동 처리 알림</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCompensator {

    private final OrderRepository orderRepository;
    private final HubClient hubClient;
    private final DeliveryClient deliveryClient;
    private final SlackNotifier slackNotifier;
    private final TransactionTemplate transactionTemplate;

    /**
     * 재고 복원을 요청하고 결과에 따라 보상 상태를 변경한다.
     * 성공 시 보상 상태 COMPLETED, 실패 시 보상 상태 FAILED + 수동 처리
     *
     * @param order  재고 복원 대상 주문 엔티티
     * @param reason 보상 실패 시 수동 처리 알림 사유
     */
    public void restoreStock(Order order, String reason) {
        finishCompensation(order, callRestoreStock(order), reason);
    }

    /**
     * 재고 복원과 배송 취소를 요청하고 보상 결과를 기록한다.
     * 둘 다 성공해야 보상 COMPLETED, 하나라도 실패면 FAILED
     *
     * @param order  보상 처리 대상 주문 엔티티
     * @param reason 보상 실패 시 수동 처리 알림 사유
     */
    public void restoreStockAndCancelDelivery(Order order, String reason) {
        boolean restored = callRestoreStock(order);
        boolean cancelled = callCancelDelivery(order);
        finishCompensation(order, restored && cancelled, reason);
    }

    /**
     * 배송 취소 타임아웃 등 결과를 확인할 수 없거나 예상하지 못한 예외가 발생하는 경우,
     * 보상 상태를 FAILED로 변경하고 Slack 수동 처리 알림을 전송한다.
     *
     * @param order  보상 실패 처리할 주문 엔티티
     * @param reason 수동 처리 알림에 포함할 사유
     */
    public void failCompensation(Order order, String reason) {
        finishCompensation(order, false, reason);
    }

    /**
     * Hub의 재고 복원을 요청하고 성공 여부를 반환한다.
     * <p>
     * Hub에서 orderId 기준 중복 복원 방지, 차감 기록이 없으면 복원 없이 성공 처리
     *
     * @param order 재고 복원 대상 주문
     * @return 복원 요청 성공 여부
     */
    private boolean callRestoreStock(Order order) {
        try {
            hubClient.restoreStock(new StockRequest(
                    order.getDepartureHubId(), order.getProductId(), order.getQuantity(), order.getId()));
            log.debug("[보상] 재고 복원 성공 orderId={}", order.getId());
            return true;
        } catch (RuntimeException e) {
            log.error("[수동 처리 필요] 재고 복원 실패 orderId={}, hubId={}, productId={}, quantity={}",
                    order.getId(), order.getDepartureHubId(), order.getProductId(), order.getQuantity(), e);
            return false;
        }
    }

    /**
     * Delivery에 배송 취소를 요청한다.
     * Delivery API는 배송이 없거나 이미 취소된 경우에도 200을 반환한다.
     *
     * @param order 배송을 취소할 주문
     * @return 배송 취소 요청 성공 여부
     */
    private boolean callCancelDelivery(Order order) {
        try {
            deliveryClient.cancelDelivery(new DeliveryCancelRequest(order.getId()));
            log.debug("[보상] 배송 취소 성공 orderId={}", order.getId());
            return true;
        } catch (RuntimeException e) {
            log.error("[수동 처리 필요] 배송 취소 실패 orderId={}", order.getId(), e);
            return false;
        }
    }

    /**
     * 보상 결과를 기록한다.
     * 성공 → 보상 COMPLETED / 실패 → 보상 FAILED (수동 처리 대상)
     *
     * @param order   기록할 주문 엔티티
     * @param success 보상 작업 성공 여부
     * @param reason  보상 실패 시 수동 처리 알림 사유
     */
    private void finishCompensation(Order order, boolean success, String reason) {
        if (success) {
            transactionTemplate.executeWithoutResult(status -> orderRepository.findByIdOrThrow(order.getId()).completeCompensation());
            log.info("[보상] 완료 orderId={} → 보상 COMPLETED", order.getId());
            return;
        }
        Order failed = transactionTemplate.execute(status -> {
            Order saved = orderRepository.findByIdOrThrow(order.getId());
            saved.failCompensation();
            return saved;
        });
        log.error("[수동 처리 필요] 보상 실패 orderId={} → 보상 FAILED", order.getId());
        slackNotifier.notifyManualAction(failed, reason);
    }
}
