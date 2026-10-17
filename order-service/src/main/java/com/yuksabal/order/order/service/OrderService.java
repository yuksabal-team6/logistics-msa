package com.yuksabal.order.order.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.order.global.exception.OrderErrorCode;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.DeliveryCreateRequest;
import com.yuksabal.order.order.client.dto.DeliveryCreateResponse;
import com.yuksabal.order.order.client.dto.ProductResponse;
import com.yuksabal.order.order.client.dto.StockRequest;
import com.yuksabal.order.order.client.dto.UserResponse;
import com.yuksabal.order.order.dto.request.OrderCreateRequest;
import com.yuksabal.order.order.dto.request.OrderSearchRequest;
import com.yuksabal.order.order.dto.response.OrderCompleteResponse;
import com.yuksabal.order.order.dto.response.OrderCreateResponse;
import com.yuksabal.order.order.dto.response.OrderInProgressResponse;
import com.yuksabal.order.order.dto.response.OrderResponse;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.common.util.PageableUtils.toStablePageable;
import static com.yuksabal.order.order.repository.OrderSpecification.*;

// 생성: Saga, 멱등성 (결정 #17, #18) / 취소: #9, #4 (결정 #19)는 후속 이슈에서 구현

/**
 * 주문 서비스 로직을 정의한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    // 목록 정렬 허용 필드
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "totalAmount");

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final CompanyClient companyClient;
    private final HubClient hubClient;
    private final DeliveryClient deliveryClient;
    // 외부 호출을 트랜잭션 밖에 두고, DB 저장만 짧은 트랜잭션으로 묶는다
    private final TransactionTemplate transactionTemplate;

    /**
     * 주문을 생성한다.
     *
     * @param request        생성 요청 dto
     * @param idempotencyKey 멱등성 확인 키
     * @param userId         요청한 사용자 ID
     * @param role           요청한 사용자 권한
     * @return 생성된 주문 정보
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderCreateResponse createOrder(OrderCreateRequest request, UUID idempotencyKey, UUID userId, UserRole role) {

        // 0. 주문자 확인: 탈퇴 회원은 user가 404, 비활성 회원은 403 (주문 저장 없이 끝)
        UserResponse orderer = userClient.getUser(userId).data();
        if (!orderer.active()) {
            throw new BusinessException(FORBIDDEN, "비활성화된 회원은 주문할 수 없습니다.");
        }

        // 1, 2. 상품, 업체 조회 (실패하면 주문 저장 없이 끝)
        ProductResponse product = companyClient.getProduct(request.productId()).data();
        if (!product.companyId().equals(request.supplierCompanyId())) {
            throw new BusinessException(VALIDATION_ERROR, "공급 업체의 상품이 아닙니다.", "productId");
        }
        CompanyResponse supplier = companyClient.getCompany(request.supplierCompanyId()).data();
        CompanyResponse requester = companyClient.getCompany(request.requestCompanyId()).data();

        // 3. 주문 PENDING 저장 (출발 허브 = 공급 업체 허브, 도착 허브 = 요청 업체 허브)
        Order order = transactionTemplate.execute(status -> orderRepository.save(Order.builder()
                .requestCompanyId(request.requestCompanyId())
                .supplierCompanyId(request.supplierCompanyId())
                .productId(request.productId())
                .departureHubId(supplier.hubId())
                .arrivalHubId(requester.hubId())
                .ordererId(userId)
                .quantity(request.quantity())
                .unitPrice(product.unitPrice())
                .requestMessage(request.requestMessage())
                .idempotencyKey(idempotencyKey.toString())
                .build()));

        // 4. 재고 차감 (출발 허브)
        hubClient.decreaseStock(new StockRequest(
                order.getDepartureHubId(), order.getProductId(), order.getQuantity(), order.getId()));

        // 5. 배송 생성
        DeliveryCreateResponse delivery = deliveryClient.createDelivery(new DeliveryCreateRequest(
                order.getId(), order.getDepartureHubId(), order.getArrivalHubId(), order.getRequestCompanyId(),
                request.deliveryAddress(), request.recipientName(), null)).data();

        // 6. 주문 확정 (CONFIRMED + deliveryId)
        Order confirmed = transactionTemplate.execute(status -> {
            Order saved = findOrder(order.getId());
            saved.confirm(delivery.deliveryId());
            return saved;
        });

        // TODO 확인: 커밋 후 Slack 주문 알림 . 주문자 멘션(<@slackId>)도 구현 예정
        //  user 내부 api 8 응답에 slackId 필드가 추가되면 멘션, 그 전엔 멘션 없이 알림
        return OrderCreateResponse.from(confirmed);
    }


    /**
     * 주문 목록을 조회한다.
     *
     * @param request  검색 요청 dto
     * @param pageable
     * @param userId   요청한 사용자 ID
     * @param role     요청한 사용자 role
     * @return 페이징된 주문 목록 응답
     */
    public PageResponse<OrderResponse> getOrders(OrderSearchRequest request, Pageable pageable, UUID userId, UserRole role) {
        Specification<Order> spec = Specification
                // MASTER는 주문자 조건을 제외(null)하고, 그 외 사용자는 본인 주문으로 제한한다.
                .where(ordererIdEquals(role == UserRole.MASTER ? null : userId))
                .and(requestCompanyIdEquals(request.requestCompanyId()))
                .and(supplierCompanyIdEquals(request.supplierCompanyId()))
                .and(productIdEquals(request.productId()))
                .and(statusEquals(request.status()))
                .and(createdFrom(request.createdFrom()))
                .and(createdTo(request.createdTo()));

        Page<Order> orders = orderRepository.findAll(spec, toStablePageable(pageable, SORTABLE_FIELDS));
        return PageResponse.from(orders, OrderResponse::from);
    }

    /**
     * 단건 주문을 조회한다.
     * MASTER 또는 주문자 본인만 조회 가능
     *
     * @param orderId 조회할 주문 ID
     * @param userId  요청한 사용자 ID
     * @param role    요청한 사용자의 권한
     * @return 조회된 주문 정보
     */
    public OrderResponse getOrder(UUID orderId, UUID userId, UserRole role) {
        Order order = findOrder(orderId);
        if (role != UserRole.MASTER && !order.isOrderedBy(userId)) {
            throw new BusinessException(FORBIDDEN, "본인 주문만 조회할 수 있습니다.");
        }
        return OrderResponse.from(order);
    }

    /**
     * 주문을 취소한다.
     *
     * @param orderId 취소할 주문 ID
     * @param userId  요청한 사용자 ID
     * @param role    요청한 사용자 권한
     * @return 취소된 주문 정보
     */
    public OrderResponse cancelOrder(UUID orderId, UUID userId, UserRole role) {
        throw new BusinessException(OrderErrorCode.NOT_IMPLEMENTED);
    }

    /**
     * 주문을 삭제한다. (soft delete)
     * MASTER만 삭제 가능, 완료된 주문 또는 보상 처리가
     * 완료되었거나 필요 없는 실패/취소 주문만 삭제할 수 있다.
     *
     * @param orderId 삭제할 주문 ID
     * @param userId  요청한 사용자 ID
     * @param role    요청한 사용자 권한
     */
    @Transactional
    public void deleteOrder(UUID orderId, UUID userId, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN, "주문은 MASTER만 삭제할 수 있습니다.");
        }
        Order order = findOrder(orderId);
        if (!order.isDeletable()) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_DELETABLE,
                    "완료된 주문 또는 보상이 끝난 실패/취소 주문만 삭제할 수 있습니다.", "orderId");
        }
        order.delete(userId);
    }

    /**
     * 배송 완료 시 주문을 완료 처리한다. (delivery 호출)
     * CONFIRMED 주문만 COMPLETED로 변경, 이미 COMPLETED면 변경하지 않고 응답한다.
     *
     * @param orderId 완료 처리할 주문 ID
     * @return 주문 ID와 상태
     */
    @Transactional
    public OrderCompleteResponse completeOrder(UUID orderId) {
        Order order = findOrder(orderId);
        order.complete();
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
        return new OrderInProgressResponse(ordererId, exists);
    }

    /**
     * 주문 ID로 주문을 조회한다.
     * 없거나 삭제된 주문이면 예외를 발생한다.
     *
     * @param orderId 존재 여부를 확인할 주문 ID
     * @return 존재하는 주문 객체
     */
    private Order findOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 주문입니다.", "orderId"));
    }
}
