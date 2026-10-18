package com.yuksabal.order.order.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
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
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        // TODO ⑤ 멱등성: 같은 Idempotency-Key 주문이 있으면 기존 결과 응답 (CONFIRMED면 그 결과, FAILED면 실패 결과, PENDING이면 409 처리 중)
        //  동시에 같은 키가 들어오면 idempotency_key 부분 유니크 위반 → 기존 주문 응답 (schema.sql 필요, 결정 18)

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
        log.debug("[주문 생성] 3. PENDING 저장 orderId={}, orderNumber={}", order.getId(), order.getOrderNumber());

        // 3-1. 저장 후 주문자 재확인 (회원 삭제와 동시 요청 경합 방지)
        // user 삭제는 비활성화 → 진행 중 주문 확인 순서라, PENDING 저장 뒤 다시 확인하면 어느 순서로 겹쳐도 한쪽에서 걸림
        // 재고 차감 전이라 되돌릴 것 없음 → 실패(비활성 403, 탈퇴 404, 호출 실패)면 FAILED + NOT_REQUIRED
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
        }
        // TODO ④ 재고 차감 5xx/타임아웃: 재시도는 common Feign이 자동으로 함 (order에서 재시도 직접 구현 x, 겹치면 최대 9번 호출)
        //  재시도까지 실패하면 FeignServerException(5xx), RetryableException(타임아웃)이 옴 → 이 둘만 잡아서
        //  FAILED + PENDING → 재고 복원(차감 여부 모름) → COMPLETED 또는 FAILED

        // TODO ④ 배송 생성 5xx/타임아웃: 재시도는 common Feign이 자동으로 함 (order에서 재시도 직접 구현 금지)
        //  최종 예외 FeignServerException(5xx), RetryableException(타임아웃)을 잡아서 FAILED + PENDING → 재고 복원
        //  타임아웃(RetryableException)이었으면 배송 취소(내부 API 9)도 함께 호출. 둘 다 성공해야 보상 COMPLETED

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
        }
        log.debug("[주문 생성] 5. 배송 생성 성공 orderId={}, deliveryId={}", order.getId(), delivery.deliveryId());

        // 6. 주문 확정 (CONFIRMED + deliveryId)
        Order confirmed = transactionTemplate.execute(status -> {
            Order saved = findOrder(order.getId());
            saved.confirm(delivery.deliveryId());
            return saved;
        });
        log.debug("[주문 생성] 6. CONFIRMED 확정 orderId={}, orderNumber={}", confirmed.getId(), confirmed.getOrderNumber());

        // TODO ⑤ 보상 상태가 FAILED가 되면 같은 Webhook으로 [수동 처리 필요] 알림 (주문 ID, 실패 단계)
        // TODO ⑤ order schema.sql: idempotency_key, order_number 부분 유니크 인덱스 + sql.init.mode always
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
        // TODO 이슈 51 주문 취소: CONFIRMED → CANCELLED + 보상 PENDING → 배송 취소(내부 API 9, 실패 시 CONFIRMED + NOT_REQUIRED로 되돌리고 409/502)
        //  → 재고 복원(내부 API 4) → 보상 COMPLETED/FAILED. 권한: 주문자, 요청 업체 관리자, MASTER
        //  DELIVERY_CONFLICT(409) 처리 방식(재시도 또는 그대로 409) 확정 필요
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

    // 주문자 확인 (user 내부 API 8). 탈퇴 회원은 user가 404 → FeignClientException 그대로, 비활성 회원은 403

    /**
     * 회원 ID로 주문자가 활성화된 계정인지 확인한다.
     *
     * @param userId 주문을 요청한 회원의 ID
     * @param step 로그용 문자열
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
     * Hub에 재고 복원을 요청한다.
     * Hub에서 orderId 기준 중복 복원 방지, 차감 기록이 없으면 복원 없이 성공 처리
     * 성공 시 보상 상태 COMPLETED, 실패 시 보상 상태 FAILED + 수동 처리
     *
     * @param order 재고 복원 대상 주문 엔티티
     */
    private void restoreStock(Order order) {
        try {
            hubClient.restoreStock(new StockRequest(
                    order.getDepartureHubId(), order.getProductId(), order.getQuantity(), order.getId()));
            transactionTemplate.executeWithoutResult(status -> findOrder(order.getId()).completeCompensation());
            log.debug("[주문 생성] 보상 재고 복원 성공 orderId={} → 보상 COMPLETED", order.getId());
        } catch (RuntimeException e) {
            // TODO ⑤ 여기서 Slack [수동 처리 필요] 알림
            log.error("[수동 처리 필요] 재고 복원 실패 orderId={}, hubId={}, productId={}, quantity={} → 보상 FAILED",
                    order.getId(), order.getDepartureHubId(), order.getProductId(), order.getQuantity(), e);
            transactionTemplate.executeWithoutResult(status -> findOrder(order.getId()).failCompensation());
        }
    }

    /**
     * 주문 실패 시 주문 상태/보상 상태를 변경, 저장한다.
     * 별도의 짧은 트랜잭션에서 처리한다.
     *
     * @param orderId 상태를 변경할 주문 ID
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
