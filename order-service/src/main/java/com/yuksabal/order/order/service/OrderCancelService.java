package com.yuksabal.order.order.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.order.order.client.CompanyClient;
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

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;

/**
 * 주문을 취소한다.
 * <ul>
 *     <li>주문 CANCELLED → 배송 취소 → 재고 복원 순서</li>
 *     <li>외부 호출은 트랜잭션 밖, DB 저장만 짧은 트랜잭션 (TransactionTemplate)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private static final String CANCEL_REASON = "주문 취소 보상 실패 (재고 복원)";
    private static final String DELIVERY_UNKNOWN_REASON = "주문 취소 중 배송 취소 결과 확인 불가 (타임아웃 등)";
    // delivery 배송 취소 응답: 배송이 이미 완료됨 
    private static final String DELIVERY_ALREADY_COMPLETED = "DELIVERY_ALREADY_COMPLETED";

    private final OrderRepository orderRepository;
    private final CompanyClient companyClient;
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

        // 0. 권한: 주문자 본인, 요청 업체 관리자, MASTER (아니면 403)
        Order target = transactionTemplate.execute(status -> orderRepository.findByIdOrThrow(orderId));
        verifyCancelPermission(target, userId, role);
        log.debug("[주문 취소] 0. 권한 확인 통과 orderId={}", orderId);

        // 1. 주문 CANCELLED + 보상 PENDING (CONFIRMED가 아니면 409)
        // 행 잠금: 같은 주문 동시 취소 시 뒤 요청은 앞 요청 커밋 후 CANCELLED를 읽고 409 (배송 취소, 재고 복원 중복 호출 방지)
        Order order;
        try {
            order = transactionTemplate.execute(status -> {
                Order found = orderRepository.findByIdForUpdateOrThrow(orderId);
                found.cancel();
                return found;
            });
        } catch (BusinessException e) {
            log.warn("[주문 취소] 1. 취소 불가 orderId={}, status={}, code={}", orderId, target.getStatus(), e.getErrorCode());
            throw e;
        }
        log.debug("[주문 취소] 1. CANCELLED 저장 orderId={}", orderId);

        // 2. 배송, 경로 기록 취소 (내부 API 9). 실패하면 D-28대로 처리하고 예외를 그대로 던짐 (재고 복원 안 함)
        cancelDelivery(order);

        // 3. 재고 복원 (내부 API 4) → 보상 COMPLETED, 실패면 보상 FAILED + Slack 수동 처리 (취소는 유지)
        orderCompensator.restoreStock(order, CANCEL_REASON);

        Order cancelled = transactionTemplate.execute(status -> orderRepository.findByIdOrThrow(orderId));
        log.info("[주문 취소] 완료 orderId={}, 보상={}", orderId, cancelled.getCompensationStatus());
        return OrderResponse.from(cancelled);
    }

    // delivery가 거절(4xx)하거나 실패로 응답(5xx) → 배송이 안 취소된 게 확실하니 CONFIRMED + NOT_REQUIRED로 되돌림 (4xx는 그대로, 5xx는 502)
    // 타임아웃, 예상 못 한 예외 → 배송 취소 여부를 모름. CANCELLED 유지 + 보상 FAILED + Slack 수동 처리 (D-28)
    private void cancelDelivery(Order order) {
        try {
            deliveryClient.cancelDelivery(new DeliveryCancelRequest(order.getId()));
            log.debug("[주문 취소] 2. 배송 취소 성공 orderId={}", order.getId());
        } catch (FeignClientException e) {

            // 배송 이미 완료된 경우 -> 주문도 완료 처리
            //   취소 중 배송 완료 알림이 거절될 수 있음. 되돌리면 배송 completed + 주문 confirmed로 불일치 발생됨.
            if (DELIVERY_ALREADY_COMPLETED.equals(e.getCode())) {
                completeInsteadOfCancel(order);
                log.warn("[주문 취소] 2. 배송 이미 완료 orderId={} → 취소 불가, 주문 COMPLETED", order.getId());
                throw e;
            }
            revertCancel(order);
            log.warn("[주문 취소] 2. 배송 취소 거절 orderId={}, status={}, code={} → CONFIRMED + NOT_REQUIRED",
                    order.getId(), e.getStatus(), e.getCode());
            throw e;
        } catch (FeignServerException e) {
            revertCancel(order);
            log.warn("[주문 취소] 2. 배송 취소 5xx orderId={}, status={} → CONFIRMED + NOT_REQUIRED",
                    order.getId(), e.getStatus());
            throw e;
        } catch (RuntimeException e) {
            log.error("[수동 처리 필요] 2. 배송 취소 결과 확인 불가 orderId={} → CANCELLED 유지 + 보상 FAILED", order.getId(), e);
            orderCompensator.failCompensation(order, DELIVERY_UNKNOWN_REASON);
            throw e;
        }
    }

    private void revertCancel(Order order) {
        transactionTemplate.executeWithoutResult(status -> orderRepository.findByIdOrThrow(order.getId()).revertCancel());
    }

    /**
     * 배송 완료된 경우 주문 취소를 되돌리고 완료 처리 한다.
     * 동일 트랜잭션에서 처리하여 중간 상태 (CONFIRMED)가 외부에 노출되지 않도록 한다.
     *
     * @param order 완료 처리할 주문
     */
    private void completeInsteadOfCancel(Order order) {
        transactionTemplate.executeWithoutResult(status -> {
            Order found = orderRepository.findByIdOrThrow(order.getId());
            found.revertCancel();
            found.complete();
        });
    }

    /**
     * 주문 취소 가능한 권한인지 확인한다.
     * <ul>
     * <li>주문자 본인, MASTER는 company 호출 없이 통과,
     * COMPANY 만 요청 업체 관리자(ownerId)인지 확인한다.</li>
     * <li>USER, DELIVERY 는 company 호출 없이 403 응답이다.</li>
     * <li>company OwnerPermission과 동일하게 업체 소유권 검증 (결정 19 역할 + 소유권)</li>
     * </ul>
     *
     * @param order  확인 대상 주문 엔티티
     * @param userId 확인할 사용자 ID
     * @param role   확인할 사용자의 권한
     */
    private void verifyCancelPermission(Order order, UUID userId, UserRole role) {
        if (role == UserRole.MASTER || order.isOrderedBy(userId)
                || (role == UserRole.COMPANY && isRequestCompanyOwner(order, userId))) {
            return;
        }
        log.warn("[주문 취소] 권한 없음 orderId={}, userId={}, role={}", order.getId(), userId, role);
        throw new BusinessException(FORBIDDEN, "주문자, 요청 업체 관리자, MASTER만 주문을 취소할 수 있습니다.");
    }

    /**
     * 요청 업체의 관리자인지 확인한다.
     * <ul>
     *   <li>업체 조회 404: 관리자 확인 불가로 false 반환</li>
     *   <li>그 외 오류(5xx, 타임아웃): 예외 전파</li>
     * </ul>
     *
     * @param order  확인 대상 주문 엔티티
     * @param userId 확인할 사용자 ID
     * @return 요청 업체 관리자이면 true, 아니면 false
     */
    private boolean isRequestCompanyOwner(Order order, UUID userId) {
        try {
            return userId.equals(companyClient.getCompany(order.getRequestCompanyId()).data().ownerId());
        } catch (FeignClientException e) {
            if (e.getStatus() == 404) {
                return false;
            }
            throw e;
        }
    }
}
