package com.yuksabal.order.order.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.order.global.exception.OrderErrorCode;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.DeliveryCancelRequest;
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
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
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
@Slf4j
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
    private final SlackNotifier slackNotifier;
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

        log.debug("[주문 생성] 시작 userId={}, productId={}, quantity={}, idempotencyKey={}",
                userId, request.productId(), request.quantity(), idempotencyKey);

        // 멱등성: 같은 Idempotency-Key로 만든 주문이 있으면 새로 만들지 않고 기존 결과로 응답
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey.toString());
        if (existing.isPresent()) {
            return replayExisting(existing.get(), request, userId);
        }

        // 0. 주문자 확인: 탈퇴 회원은 user에서 404, 비활성 회원은 403 (주문 저장 없이 끝)
        verifyOrdererActive(userId, "0. 주문자 확인");

        // 1, 2. 상품, 업체 조회 (실패하면 주문 저장 없이 끝)
        ProductResponse product = companyClient.getProduct(request.productId()).data();
        if (!product.companyId().equals(request.supplierCompanyId())) {
            throw new BusinessException(VALIDATION_ERROR, "공급 업체의 상품이 아닙니다.", "productId");
        }
        CompanyResponse supplier = companyClient.getCompany(request.supplierCompanyId()).data();
        CompanyResponse requester = companyClient.getCompany(request.requestCompanyId()).data();
        log.debug("[주문 생성] 1, 2. 조회 완료 unitPrice={}, 출발 허브(공급 업체)={}, 도착 허브(요청 업체)={}",
                product.unitPrice(), supplier.hubId(), requester.hubId());

        // 3. 주문 PENDING 저장 (출발 허브 = 공급 업체 허브, 도착 허브 = 요청 업체 허브)
        Order order;
        try {
            order = transactionTemplate.execute(status -> orderRepository.saveAndFlush(Order.builder()
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
        } catch (DataIntegrityViolationException e) {

            // 같은 키 요청이 동시에 들어와 먼저 저장된 데이터가 있다면 (idempotency_key 부분 유니크 인덱스)
            //  → 해당 주문 기준으로 응답
            Order first = orderRepository.findByIdempotencyKey(idempotencyKey.toString()).orElseThrow(() -> e);

            log.debug("[주문 생성] 3. 같은 Idempotency-Key 동시 요청 orderId={} → 먼저 저장된 주문으로 응답", first.getId());
            return replayExisting(first, request, userId);
        }
        log.debug("[주문 생성] 3. PENDING 저장 orderId={}, orderNumber={}", order.getId(), order.getOrderNumber());

        // 3-1. 저장 후 주문자 재확인 (회원 삭제와 동시 요청 경합 방지)
        //  user 삭제는 비활성화 → 진행 중 주문 확인 순서이므로, PENDING 저장 후 재확인하면 어느 순서로 겹쳐도 한쪽에서 걸린다.
        //  재고 차감 전이라 되돌릴 것 없음 → 실패(비활성 403, 탈퇴 404, 호출 실패)면 FAILED + NOT_REQUIRED
        try {
            verifyOrdererActive(userId, "3-1. 주문자 재확인");
        } catch (RuntimeException e) {
            log.warn("[주문 생성] 3-1. 주문자 재확인 실패 orderId={} → FAILED + NOT_REQUIRED", order.getId());
            failOrder(order.getId(), CompensationStatus.NOT_REQUIRED);
            throw e;
        }

        // 4. 출발 허브의 재고 차감
        try {
            // Hub 서비스에 재고 차감 요청
            hubClient.decreaseStock(new StockRequest(
                    order.getDepartureHubId(),
                    order.getProductId(),
                    order.getQuantity(),
                    order.getId()));

            log.debug("[주문 생성] 4. 재고 차감 성공 orderId={}", order.getId());

        } catch (FeignClientException e) {
            // 재고 차감 요청 오류

            // 4xx(재고 부족 409 등)는 재고가 안 빠짐 → 되돌릴 것 없음. hub 오류를 그대로 응답
            log.warn("[주문 생성] 4. 재고 차감 실패 orderId={}, status={}, code={} → FAILED + NOT_REQUIRED",
                    order.getId(), e.getStatus(), e.getCode());

            // 주문 상태 FAILED, 보상 상태 NOT_REQUIRED 처리
            failOrder(order.getId(), CompensationStatus.NOT_REQUIRED);
            throw e;
        } catch (FeignServerException | RetryableException e) {
            // 5xx/타임아웃: 재시도는 common Feign이 이미 함 (order에서 재시도 구현 안 함, 겹치면 최대 9번 호출)
            // 차감됐는지 모름 → 재고 복원 호출 (차감 기록 없으면 hub가 0개 복원으로 성공 처리). 응답은 502
            log.warn("[주문 생성] 4. 재고 차감 5xx/타임아웃 orderId={}, exception={} → FAILED + PENDING, 재고 복원",
                    order.getId(), e.getClass().getSimpleName());
            failOrder(order.getId(), CompensationStatus.PENDING);
            restoreStock(order);
            throw e;
        }

        // 5. 배송 생성
        DeliveryCreateResponse delivery;
        try {
            delivery = deliveryClient.createDelivery(new DeliveryCreateRequest(
                    order.getId(),
                    order.getDepartureHubId(),
                    order.getArrivalHubId(),
                    order.getRequestCompanyId(),
                    request.deliveryAddress(),
                    request.recipientName(),
                    null
            )).data();
        } catch (FeignClientException e) {
            log.warn("[주문 생성] 5. 배송 생성 실패 orderId={}, status={}, code={} → FAILED + PENDING, 재고 복원",
                    order.getId(), e.getStatus(), e.getCode());

            // 4xx(허브 경로 없음 등)는 배송 생성 실패를 의미 -> 재고 차감된 상태이므로 재고 복원 후 delivery 오류 그대로 응답
            failOrder(order.getId(), CompensationStatus.PENDING);
            restoreStock(order);
            throw e;
        } catch (FeignServerException e) {
            // 5xx: 재시도까지 실패. delivery가 오류로 응답했으니 배송은 안 만들어짐 → 재고만 복원. 응답은 502
            log.warn("[주문 생성] 5. 배송 생성 5xx orderId={}, status={} → FAILED + PENDING, 재고 복원",
                    order.getId(), e.getStatus());
            failOrder(order.getId(), CompensationStatus.PENDING);
            restoreStock(order);
            throw e;
        } catch (RetryableException e) {
            // 타임아웃인 경우: 배송이 만들어졌는지 모름
            // → 재고 복원 + 배송 취소 둘 다 (배송 존재하지 않으면 delivery가 200). 응답은 502
            log.warn("[주문 생성] 5. 배송 생성 타임아웃 orderId={} → FAILED + PENDING, 재고 복원 + 배송 취소", order.getId());
            failOrder(order.getId(), CompensationStatus.PENDING);
            restoreStockAndCancelDelivery(order);
            throw e;
        }
        log.debug("[주문 생성] 5. 배송 생성 성공 orderId={}, deliveryId={}", order.getId(), delivery.deliveryId());

        // 6. 주문 확정 (CONFIRMED + deliveryId)
        Order confirmed = transactionTemplate.execute(status -> {
            Order saved = findOrder(order.getId());
            saved.confirm(delivery.deliveryId());
            return saved;
        });
        log.debug("[주문 생성] 6. CONFIRMED 확정 orderId={}, orderNumber={}", confirmed.getId(), confirmed.getOrderNumber());

        // 7. Slack 주문 알림 (확정 커밋 후). 실패해도 주문은 유지 (SlackNotifier가 예외를 삼킴)
        slackNotifier.notifyOrderCreated(confirmed, product.productName(), supplier.companyName(), requester.companyName());
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
        // TODO 이슈 51 주문 취소: CONFIRMED → CANCELLED + 보상 PENDING → 배송 취소(내부 API 9, 실패 시 CONFIRMED + NOT_REQUIRED로 되돌리고 409/502)
        //  → 재고 복원(내부 API 4) → 보상 COMPLETED/FAILED. 권한: 주문자, 요청 업체 관리자, MASTER
        //  DELIVERY_CONFLICT(409) 처리 방식(재시도 또는 그대로 409) 확정 필요
        throw new BusinessException(OrderErrorCode.NOT_IMPLEMENTED);
    }

    /**
     * 주문을 삭제한다. (soft delete)
     * <p>
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
     * 동일한 Idempotency-Key로 재요청 시 기존 주문 상태에 따라 처리한다.
     * 다른 회원의 요청, 처리 중이거나 실패한 주문은 409 예외를 발생시킨다.
     * 그 외 상태는 기존 주문 정보를 반환한다.
     *
     * @param existing 동일한 키로 저장된 기존 주문 엔티티
     * @param userId   재요청한 회원 ID
     * @return 기존 주문 생성 응답
     */
    private OrderCreateResponse replayExisting(Order existing, OrderCreateRequest request, UUID userId) {
        log.debug("[주문 생성] 같은 Idempotency-Key 재요청 orderId={}, status={}", existing.getId(), existing.getStatus());
        if (!existing.isOrderedBy(userId)) {
            // UUID라 우연히 겹칠 일은 거의 없음 → 다른 회원 키 재사용은 비정상 요청
            log.warn("[주문 생성] 다른 회원의 Idempotency-Key 사용 orderId={}, 요청 userId={}", existing.getId(), userId);
            throw new BusinessException(OrderErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (!existing.isSameRequest(request.requestCompanyId(), request.supplierCompanyId(),
                request.productId(), request.quantity())) {
            // 같은 키로 다른 주문 내용 → 기존 결과를 돌려주면 사용자가 다른 주문으로 착각함
            log.warn("[주문 생성] 같은 Idempotency-Key로 다른 주문 내용 orderId={}", existing.getId());
            throw new BusinessException(OrderErrorCode.IDEMPOTENCY_KEY_MISMATCH);
        }
        if (existing.getStatus() == OrderStatus.PENDING) {
            throw new BusinessException(OrderErrorCode.ORDER_IN_PROGRESS);
        }
        if (existing.getStatus() == OrderStatus.FAILED) {
            throw new BusinessException(OrderErrorCode.ORDER_ALREADY_FAILED,
                    OrderErrorCode.ORDER_ALREADY_FAILED.getMessage(), "orderId");
        }
        return OrderCreateResponse.from(existing);
    }

    /**
     * 회원 ID로 주문자가 활성화된 계정인지 확인한다.
     * 탈퇴 회원은 user가 404 → FeignClientException 그대로, 비활성 회원은 403
     *
     * @param userId 주문을 요청한 회원의 ID
     * @param step   로그용 문자열
     */
    private void verifyOrdererActive(UUID userId, String step) {
        UserResponse orderer = userClient.getUser(userId).data();
        log.debug("[주문 생성] {} userId={}, active={}", step, userId, orderer.active());
        if (!orderer.active()) {
            // TODO common에 ACCOUNT_DISABLED(403)가 추가되면 그 코드로 교체 (user 명세 변경 공지)
            throw new BusinessException(FORBIDDEN, "비활성화된 회원은 주문할 수 없습니다.");
        }
    }

    /**
     * 재고 복원을 요청하고 결과에 따라 보상 상태를 변경한다.
     * 성공 시 보상 상태 COMPLETED, 실패 시 보상 상태 FAILED + 수동 처리
     *
     * @param order 재고 복원 대상 주문 엔티티
     */
    private void restoreStock(Order order) {
        finishCompensation(order, callRestoreStock(order));
    }

    /**
     * 재고 복원과 배송 취소를 요청하고 보상 결과를 기록한다.
     * 둘 다 성공해야 보상 COMPLETED, 하나라도 실패면 FAILED
     *
     * @param order 보상 처리 대상 주문 엔티티
     */
    private void restoreStockAndCancelDelivery(Order order) {
        boolean restored = callRestoreStock(order);
        boolean cancelled = callCancelDelivery(order);
        finishCompensation(order, restored && cancelled);
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
            log.debug("[주문 생성] 보상 재고 복원 성공 orderId={}", order.getId());
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
            log.debug("[주문 생성] 보상 배송 취소 성공 orderId={}", order.getId());
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
     */
    private void finishCompensation(Order order, boolean success) {
        if (success) {
            transactionTemplate.executeWithoutResult(status -> findOrder(order.getId()).completeCompensation());
            log.debug("[주문 생성] 보상 완료 orderId={} → 보상 COMPLETED", order.getId());
            return;
        }
        Order failed = transactionTemplate.execute(status -> {
            Order saved = findOrder(order.getId());
            saved.failCompensation();
            return saved;
        });
        log.error("[수동 처리 필요] 보상 실패 orderId={} → 보상 FAILED", order.getId());
        slackNotifier.notifyManualAction(failed, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");
    }

    /**
     * 주문 실패 시 주문 상태/보상 상태를 변경, 저장한다.
     * 별도의 짧은 트랜잭션에서 처리한다.
     *
     * @param orderId            상태를 변경할 주문 ID
     * @param compensationStatus 변경할 보상 상태
     */
    private void failOrder(UUID orderId, CompensationStatus compensationStatus) {
        transactionTemplate.executeWithoutResult(status -> findOrder(orderId).fail(compensationStatus));
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
