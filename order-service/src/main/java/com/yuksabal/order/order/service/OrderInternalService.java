package com.yuksabal.order.order.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.order.order.dto.response.OrderCompleteResponse;
import com.yuksabal.order.order.dto.response.OrderInProgressResponse;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.yuksabal.order.order.repository.OrderSpecification.*;

// 다른 서비스가 Feign으로 부르는 내부 API용 (OrderInternalController)
// 배송 완료 → 주문 완료(delivery), 진행 중 주문 확인(user 회원 탈퇴, company 상품/업체 삭제)
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderInternalService {

    private final OrderRepository orderRepository;

    /**
     * 배송 완료 시 주문을 완료 처리한다. (delivery 호출)
     * <p>
     * CONFIRMED 주문만 COMPLETED로 변경, 이미 COMPLETED면 변경하지 않고 응답한다.
     *
     * @param orderId 완료 처리할 주문 ID
     * @return 주문 ID와 상태
     */
    @Transactional
    public OrderCompleteResponse completeOrder(UUID orderId) {
        Order order = orderRepository.findByIdOrThrow(orderId);
        OrderStatus before = order.getStatus(); // 바꾸기 전 상태 저장
        try {
            order.complete(); // order status를 completed 로 변경
        } catch (BusinessException e) {
            log.warn("[주문 완료] 완료 불가 orderId={}, status={}", orderId, before);
            throw e;
        }
        log.info("[주문 완료] orderId={}, {}", orderId,
                before == OrderStatus.COMPLETED ? "이미 COMPLETED, 변경 없음" : before + " → COMPLETED");
        return OrderCompleteResponse.from(order);
    }

    /**
     * 회원의 진행 중 주문 존재 여부를 확인한다.
     * 논리적으로 삭제된 주문은 제외한다.
     *
     * @param ordererId 확인할 주문자 ID
     * @return 주문자의 진행 중 주문 존재 여부
     */
    public OrderInProgressResponse hasInProgressOrder(UUID ordererId) {
        boolean exists = orderRepository.exists(ordererIdEquals(ordererId).and(inProgress()));
        log.debug("[진행 중 주문] ordererId={}, 결과={}", ordererId, exists);
        return OrderInProgressResponse.ofOrderer(ordererId, exists);
    }

    /**
     * 특정 상품의 진행 중 주문 존재 여부를 확인한다.
     * company에서 상품 삭제 전 확인한다.
     *
     * @param productId 진행 중 주문 존재 여부를 확인할 상품 ID
     * @return 진행 중 주문 존재 여부를 담은 응답 DTO
     */
    public OrderInProgressResponse hasInProgressOrderByProduct(UUID productId) {
        boolean exists = orderRepository.exists(productIdEquals(productId).and(inProgress()));
        log.debug("[진행 중 주문] productId={}, 결과={}", productId, exists);
        return OrderInProgressResponse.ofProduct(productId, exists);
    }

    /**
     * 업체의 진행 중 주문 존재 여부를 확인한다.
     * company에서 업체 삭제 전 확인한다. (공급, 요청 업체 모두 확인)
     *
     * @param companyId 진행 중 주문 존재 여부를 확인할 업체 ID
     * @return 진행 중 주문 존재 여부를 담은 응답 DTO
     */
    public OrderInProgressResponse hasInProgressOrderByCompany(UUID companyId) {
        boolean exists = orderRepository.exists(
                supplierCompanyIdEquals(companyId).or(requestCompanyIdEquals(companyId)).and(inProgress()));
        log.debug("[진행 중 주문] companyId={}, 결과={}", companyId, exists);
        return OrderInProgressResponse.ofCompany(companyId, exists);
    }
}
