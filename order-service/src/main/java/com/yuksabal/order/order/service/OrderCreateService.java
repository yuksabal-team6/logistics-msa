package com.yuksabal.order.order.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.order.global.exception.OrderErrorCode;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.DeliveryCreateRequest;
import com.yuksabal.order.order.client.dto.ProductResponse;
import com.yuksabal.order.order.client.dto.StockRequest;
import com.yuksabal.order.order.client.dto.UserResponse;
import com.yuksabal.order.order.dto.request.OrderCreateRequest;
import com.yuksabal.order.order.dto.response.OrderCreateResponse;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.event.OrderEvent;
import com.yuksabal.order.order.event.OrderOutboxRecorder;
import com.yuksabal.order.order.repository.OrderRepository;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

/**
 * Saga 패턴을 이용해 주문 생성 과정을 관리한다.
 *
 * <p>주문 생성 흐름
 * <ol>
 *     <li>멱등성 키 확인, 주문자, 상품, 업체 검증</li>
 *     <li>주문 생성 (PENDING)</li>
 *     <li>주문자, 상품, 업체 활성 상태 재확인</li>
 *     <li>재고 차감 (Hub service)</li>
 *     <li>배송 생성 (Delivery service)</li>
 *     <li>주문 확정 (CONFIRMED)</li>
 *     <li>Slack 주문 알림</li>
 * </ol>
 *
 * <p>실패 시 주문을 FAIELD로 변경하고,
 * 진행 단계에 따라 재고 복원 및 취소 보상을 수행한다.
 *
 * <p>외부 API 호출은 트랜잭션 밖에서 수행하며, DB 변경만 짧은 트랜잭션으로 처리한다. (TransactionTemplate)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final CompanyClient companyClient;
    private final HubClient hubClient;
    private final DeliveryClient deliveryClient;
    // 알림 이벤트 outbox 저장 (Slack 전송은 Kafka 리스너가, #198)
    private final OrderOutboxRecorder outboxRecorder;
    // 재고 복원, 배송 취소 보상 (주문 취소와 같이 사용)
    private final OrderCompensator orderCompensator;
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
    // @Transactional(NOT_SUPPORTED)을 붙이지 않음 (#274)
    //  붙이면 트랜잭션 동기화가 켜져서, 첫 조회(findByIdempotencyKey)가 잡은 DB 커넥션을 요청이 끝날 때까지 쥐고 있음
    //  그 상태로 확정 단계 TransactionTemplate이 커넥션을 하나 더 잡아서 요청 1건이 2개를 씀 → 동시 요청이 풀 크기만큼 오면 서로 기다리다 타임아웃
    //  붙이지 않아도 이 메서드는 트랜잭션 밖이고, 조회는 호출마다 커넥션을 잡았다 바로 반납함
    public OrderCreateResponse createOrder(OrderCreateRequest request, UUID idempotencyKey, UUID userId, UserRole role) {
        log.debug("[주문 생성] 시작 userId={}, productId={}, quantity={}, idempotencyKey={}",
                userId, request.productId(), request.quantity(), idempotencyKey);

        // 멱등성: 같은 Idempotency-Key로 만든 주문이 있으면 새로 만들지 않고 기존 결과로 응답
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey.toString());
        if (existing.isPresent()) {
            return replayExisting(existing.get(), request, userId);
        }

        // 0. 주문자 확인: 탈퇴 회원은 user에서 404, 비활성 회원은 403 (주문 저장 없이 끝)
        UserResponse orderer = verifyOrdererActive(userId, "0. 주문자 확인");
        OrderSource source = loadProductAndCompanies(request);

        Order order;
        try {
            order = savePendingOrder(request, source, idempotencyKey, userId);
        } catch (DataIntegrityViolationException e) {
            return replayConcurrentRequest(idempotencyKey, request, userId, e);
        }

        recheckOrderer(order, userId);
        recheckProductAndCompanies(order);
        decreaseStock(order);
        UUID deliveryId = createDelivery(order, request);
        // 6, 7. 주문 확정 + 주문 접수 알림 이벤트를 outbox에 같이 저장 (Slack 전송은 Kafka 리스너가 따로, #198)
        Order confirmed = confirmOrder(order, deliveryId, source, orderer);
        return OrderCreateResponse.from(confirmed);
    }

    // 1, 2. 상품, 업체 조회 (실패하면 주문 저장 없이 끝)
    private OrderSource loadProductAndCompanies(OrderCreateRequest request) {
        ProductResponse product = companyClient.getProduct(request.productId()).data();
        if (!product.companyId().equals(request.supplierCompanyId())) {
            throw new BusinessException(VALIDATION_ERROR, "공급 업체의 상품이 아닙니다.", "productId");
        }

        // 비활성 상태인지 확인
        // 공급 업체가 비활성이면 상품 active도 false로 와서, 공급 업체를 먼저 확인해야 "비활성 공급 업체" 메시지가 나감.
        CompanyResponse supplier = companyClient.getCompany(request.supplierCompanyId()).data();
        validateActive(supplier.active(), "supplierCompanyId", "비활성 공급 업체의 상품은 주문할 수 없습니다.");
        CompanyResponse requester = companyClient.getCompany(request.requestCompanyId()).data();
        validateActive(requester.active(), "requestCompanyId", "비활성 업체로는 주문할 수 없습니다.");
        validateActive(product.active(), "productId", "비활성 상품은 주문할 수 없습니다.");
        log.debug("[주문 생성] 1, 2. 조회 완료 unitPrice={}, 출발 허브(공급 업체)={}, 도착 허브(요청 업체)={}",
                product.unitPrice(), supplier.hubId(), requester.hubId());
        return new OrderSource(product, supplier, requester);
    }

    /**
     * 요청/공급 업체와 상품의 active 필드가 비활성인 경우 예외를 발생시킨다.
     * 비활성 상품, 업체는 주문할 수 없다.
     *
     * @param active  확인 대상 객체의 활성 상태
     * @param field   확인 대상 필드명
     * @param message 에러 메시지
     */
    private void validateActive(boolean active, String field, String message) {
        if (!active) {
            log.warn("[주문 생성] 1, 2. 비활성 {} → 422", field);
            throw new BusinessException(BUSINESS_RULE_VIOLATION, message, field);
        }
    }

    // 3. 주문 PENDING 저장 (출발 허브 = 공급 업체 허브, 도착 허브 = 요청 업체 허브)
    // 같은 키 동시 요청이면 idempotency_key 부분 유니크 인덱스 위반(DataIntegrityViolationException)
    private Order savePendingOrder(OrderCreateRequest request, OrderSource source, UUID idempotencyKey, UUID userId) {
        Order order = transactionTemplate.execute(status -> orderRepository.saveAndFlush(Order.builder()
                .requestCompanyId(request.requestCompanyId())
                .supplierCompanyId(request.supplierCompanyId())
                .productId(request.productId())
                .departureHubId(source.supplier().hubId())
                .arrivalHubId(source.requester().hubId())
                .ordererId(userId)
                .quantity(request.quantity())
                .unitPrice(source.product().unitPrice())
                .requestMessage(request.requestMessage())
                .idempotencyKey(idempotencyKey.toString())
                .build()));
        log.debug("[주문 생성] 3. PENDING 저장 orderId={}, orderNumber={}", order.getId(), order.getOrderNumber());
        return order;
    }

    // 같은 키 요청이 동시에 들어와 먼저 저장된 데이터가 있다면 (idempotency_key 부분 유니크 인덱스)
    //  → 해당 주문 기준으로 응답
    private OrderCreateResponse replayConcurrentRequest(UUID idempotencyKey, OrderCreateRequest request, UUID userId,
                                                        DataIntegrityViolationException e) {
        Order first = orderRepository.findByIdempotencyKey(idempotencyKey.toString()).orElseThrow(() -> e);
        log.debug("[주문 생성] 3. 같은 Idempotency-Key 동시 요청 orderId={} → 먼저 저장된 주문으로 응답", first.getId());
        return replayExisting(first, request, userId);
    }

    // 3-1. 저장 후 주문자 재확인 (회원 삭제와 동시 요청 경합 방지)
    //  user 삭제는 비활성화 → 진행 중 주문 확인 순서이므로, PENDING 저장 후 재확인하면 어느 순서로 겹쳐도 한쪽에서 걸린다.
    //  재고 차감 전이라 되돌릴 것 없음 → 실패(비활성 403, 탈퇴 404, 호출 실패)면 FAILED + NOT_REQUIRED
    private void recheckOrderer(Order order, UUID userId) {
        try {
            verifyOrdererActive(userId, "3-1. 주문자 재확인");
        } catch (RuntimeException e) {
            log.warn("[주문 생성] 3-1. 주문자 재확인 실패 orderId={} → FAILED + NOT_REQUIRED", order.getId());
            failOrder(order, CompensationStatus.NOT_REQUIRED);
            throw e;
        }
    }

    /**
     * 3-2. 주문 저장 후 상품과 요청 업체의 활성 상태를 재확인한다.
     * <ul>
     *     <li>업체 삭제와 주문 생성이 동시에 발생하는 상황을 방지하기 위한 검증이다.</li>
     *     <li>재확인 실패 시(비활성 422, 삭제 404, 호출 실패) 주문을 FAILED로 변경하고 보상 상태를 NOT_REQUIRED로 설정한다.</li>
     * </ul>
     *
     * @param order 재확인할 주문
     */
    private void recheckProductAndCompanies(Order order) {
        try {
            // 상품 조회 API로 상품, 공급 업체 같이 확인
            validateActive(companyClient.getProduct(order.getProductId()).data().active(),
                    "productId", "비활성 상품은 주문할 수 없습니다.");

            // 업체 조회 API로 요청 업체 확인
            validateActive(companyClient.getCompany(order.getRequestCompanyId()).data().active(),
                    "requestCompanyId", "비활성 업체로는 주문할 수 없습니다.");
        } catch (RuntimeException e) {
            log.warn("[주문 생성] 3-2. 상품, 업체 재확인 실패 orderId={} → FAILED + NOT_REQUIRED", order.getId());
            failOrder(order, CompensationStatus.NOT_REQUIRED);
            throw e;
        }
        log.debug("[주문 생성] 3-2. 상품, 업체 재확인 통과 orderId={}", order.getId());
    }

    // 4. 출발 허브의 재고 차감
    private void decreaseStock(Order order) {
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
            failOrder(order, CompensationStatus.NOT_REQUIRED);
            throw e;
        } catch (FeignServerException | RetryableException e) {
            // 5xx/타임아웃: 재시도는 common Feign이 이미 함 (order에서 재시도 구현 안 함, 겹치면 최대 9번 호출)
            // 차감됐는지 모름 → 재고 복원 호출 (차감 기록 없으면 hub가 0개 복원으로 성공 처리). 응답은 502
            log.warn("[주문 생성] 4. 재고 차감 5xx/타임아웃 orderId={}, exception={} → FAILED + PENDING, 재고 복원",
                    order.getId(), e.getClass().getSimpleName());

            failOrder(order, CompensationStatus.PENDING);
            orderCompensator.restoreStock(order, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");

            throw e;
        } catch (RuntimeException e) {
            // 예상하지 못한 예외(응답 해석 실패 등): 차감됐는지 모름 → 5xx/타임아웃과 같이 재고 복원

            log.error("[주문 생성] 4. 재고 차감 중 예상하지 못한 예외 orderId={} → FAILED + PENDING, 재고 복원", order.getId(), e);

            failOrder(order, CompensationStatus.PENDING);
            orderCompensator.restoreStock(order, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");

            notifyUnexpectedError(order, "재고 차감 중 예상하지 못한 오류");
            throw e;
        }
    }

    // 5. 배송 생성
    private UUID createDelivery(Order order, OrderCreateRequest request) {
        UUID deliveryId;
        try {
            deliveryId = deliveryClient.createDelivery(new DeliveryCreateRequest(
                    order.getId(),
                    order.getDepartureHubId(),
                    order.getArrivalHubId(),
                    order.getRequestCompanyId(),
                    request.deliveryAddress(),
                    request.recipientName(),
                    null,
                    order.getOrdererId(),
                    order.getSupplierCompanyId()
            )).data().deliveryId();
        } catch (FeignClientException e) {
            log.warn("[주문 생성] 5. 배송 생성 실패 orderId={}, status={}, code={} → FAILED + PENDING, 재고 복원",
                    order.getId(), e.getStatus(), e.getCode());

            // 4xx(허브 경로 없음 등)는 배송 생성 실패를 의미 -> 재고 차감된 상태이므로 재고 복원 후 delivery 오류 그대로 응답
            failOrder(order, CompensationStatus.PENDING);
            orderCompensator.restoreStock(order, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");

            throw e;
        } catch (FeignServerException e) {

            // 5xx: 재시도까지 실패. delivery가 오류로 응답했으니 배송은 안 만들어짐 → 재고만 복원. 응답은 502
            log.warn("[주문 생성] 5. 배송 생성 5xx orderId={}, status={} → FAILED + PENDING, 재고 복원",
                    order.getId(), e.getStatus());

            failOrder(order, CompensationStatus.PENDING);
            orderCompensator.restoreStock(order, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");

            throw e;
        } catch (RetryableException e) {

            // 타임아웃인 경우: 배송이 만들어졌는지 모름
            // → 재고 복원 + 배송 취소 둘 다 (배송 존재하지 않으면 delivery가 200). 응답은 502
            log.warn("[주문 생성] 5. 배송 생성 타임아웃 orderId={} → FAILED + PENDING, 재고 복원 + 배송 취소", order.getId());

            failOrder(order, CompensationStatus.PENDING);
            orderCompensator.restoreStockAndCancelDelivery(order, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");

            throw e;
        } catch (RuntimeException e) {

            // 예상하지 못한 예외(응답 해석 실패, 응답 data 없음 등): 배송이 만들어졌는지 모름 → 타임아웃과 같이 재고 복원 + 배송 취소
            log.error("[주문 생성] 5. 배송 생성 중 예상하지 못한 예외 orderId={} → FAILED + PENDING, 재고 복원 + 배송 취소", order.getId(), e);

            failOrder(order, CompensationStatus.PENDING);
            orderCompensator.restoreStockAndCancelDelivery(order, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");
            notifyUnexpectedError(order, "배송 생성 중 예상하지 못한 오류");

            throw e;
        }
        log.debug("[주문 생성] 5. 배송 생성 성공 orderId={}, deliveryId={}", order.getId(), deliveryId);
        return deliveryId;
    }

    // 6. 주문 확정 (CONFIRMED + deliveryId) + 7. 주문 접수 알림 이벤트 outbox 저장 (같은 트랜잭션, #198)
    //  둘 중 하나라도 실패하면 함께 롤백 → "확정됐는데 알림 기록 없음", "확정 안 됐는데 알림 기록 있음"이 생기지 않음
    //  주문자 slackId는 0단계에서 받은 값 (user 추가 호출 없음)
    private Order confirmOrder(Order order, UUID deliveryId, OrderSource source, UserResponse orderer) {
        Order confirmed;
        try {
            confirmed = transactionTemplate.execute(status -> {
                Order saved = orderRepository.findByIdOrThrow(order.getId());
                saved.confirm(deliveryId);
                outboxRecorder.record(OrderEvent.orderConfirmed(saved,
                        source.product().productName(), source.supplier().companyName(), source.requester().companyName(),
                        orderer.slackId()));
                return saved;
            });
        } catch (BusinessException e) { // 확정 중 상태 전이 거절:

            // 생성 흐름 밖에서 상태가 바뀐 것 → 무엇으로 바뀌었는지 모르므로 자동 보상 x, 수동 확인
            //  엔티티 내 fail()(pending -> failed)도 같은 이유로 거절되므로 타지 않음
            if (e.getErrorCode() == OrderErrorCode.INVALID_STATUS_TRANSITION) {
                handleTransitionRejected(order, "6. 주문 CONFIRM 처리", CompensationStatus.PENDING);
            }
            throw e;
        } catch (RuntimeException e) { // 확정 저장 실패(DB 오류 등):

            // 재고 차감, 배송 생성까지 끝난 상태라 PENDING으로 남으면 아무도 모름
            //  → FAILED + 재고 복원 + 배송 취소 (보상까지 실패하면 Slack 수동 처리)
            log.error("[주문 생성] 6. 주문 CONFIRM 저장 실패 orderId={} → FAILED + PENDING, 재고 복원 + 배송 취소", order.getId(), e);
            failOrder(order, CompensationStatus.PENDING);

            orderCompensator.restoreStockAndCancelDelivery(order, "주문 생성 보상 실패 (재고 복원 또는 배송 취소)");
            notifyUnexpectedError(order, "주문 확정 저장 중 예상하지 못한 오류");
            throw e;
        }
        log.info("[주문 생성] 6. CONFIRMED 확정 orderId={}, orderNumber={}", confirmed.getId(), confirmed.getOrderNumber());
        return confirmed;
    }

    /**
     * 보상이 완료된 주문에서 예상치 못한 오류가 발생하면 Slack으로 확인 알림을 보낸다.
     * <p>
     * 보상 실패 시에는 수동 처리 알림이 미리 전송되므로 완료일 때만 전송한다.
     *
     * @param order  확인 대상 주문 엔티티
     * @param reason 오류 사유
     */
    private void notifyUnexpectedError(Order order, String reason) {
        // 현재 보상 상태 확인과 확인 필요 알림 이벤트 outbox 저장을 한 트랜잭션에서 (#198)
        //  호출부의 catch 블록에서 원래 예외를 던지기 직전에 불리므로, 저장 실패(DB 장애 등)가 원래 예외를 덮어쓰지 않도록 로그만 남김
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Order current = orderRepository.findByIdOrThrow(order.getId());
                if (current.getCompensationStatus() == CompensationStatus.COMPLETED) {
                    outboxRecorder.record(OrderEvent.checkNeeded(current, reason));
                }
            });
        } catch (RuntimeException e) {
            log.error("[확인 필요] 알림 이벤트 저장 실패 orderId={}, 사유={}", order.getId(), reason, e);
        }
    }

    /**
     * 동일한 Idempotency-Key로 재요청 시 기존 주문 상태에 따라 처리한다.
     * <ul>
     *     <li>다른 회원의 요청, 처리 중이거나 실패한 주문은 409 예외를 발생시킨다.</li>
     *     <li>그 외 상태는 기존 주문 정보를 반환한다.</li>
     * </ul>
     *
     * @param existing 동일한 키로 저장된 기존 주문 엔티티
     * @param request  재요청한 주문 생성 요청 dto
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
     * @return 주문자 정보 (slackId 포함)
     */
    private UserResponse verifyOrdererActive(UUID userId, String step) {
        UserResponse orderer = userClient.getUser(userId).data();
        log.debug("[주문 생성] {} userId={}, active={}", step, userId, orderer.active());
        if (!orderer.active()) {

            // ACCOUNT_DISABLED는 user 전용 코드라 order는 공통 FORBIDDEN(403) 사용
            throw new BusinessException(FORBIDDEN, "비활성화된 회원은 주문할 수 없습니다.");
        }
        return orderer;
    }

    /**
     * 주문 실패 시 주문 상태/보상 상태를 변경, 저장한다.
     * 별도의 짧은 트랜잭션에서 처리한다.
     *
     * @param order              상태를 변경할 주문
     * @param compensationStatus 변경할 보상 상태
     */
    private void failOrder(Order order, CompensationStatus compensationStatus) {
        try {
            // DB에서 조회
            transactionTemplate.executeWithoutResult(
                    status -> orderRepository.findByIdOrThrow(order.getId())
                            .fail(compensationStatus));
        } catch (BusinessException e) {
            // 실패 처리 도중 상태 전이 거절된 경우

            //  -> 주문 상태를 알 수 없어 자동 보상 x (ex. confirmed -> failed 시도)
            //  -> 로그, slack으로 넘기고 예외를 다시 던져 뒤의 보상 플로우를 타지 않게 함.
            if (e.getErrorCode() == OrderErrorCode.INVALID_STATUS_TRANSITION) {
                handleTransitionRejected(order, "주문 실패 처리", compensationStatus);
            }
            throw e;
        }
    }

    /**
     * 주문 생성 중 상태 전이가 거절된 상황을 기록한다.
     * 보상 상태가 PENDING 이면 수동 처리 slack 알림을 전송한다.
     *
     * @param order              상태 전이가 거절된 주문
     * @param step               상태 전이가 거절된 처리 단계
     * @param compensationStatus 해당 단계에서 필요한 보상 상태 (호출한 단계가 넘긴 값. DB 값이 아님)
     */
    private void handleTransitionRejected(Order order, String step, CompensationStatus compensationStatus) {
        // 상태 전이 거절 후, DB 실제 저장된 주문 상태 확인 (재조회) -> 로그, slack 알림에 사용
        //   재조회가 실패해도 (DB 오류, 삭제 등) 원래 상태 전이 예외를 덮어쓰지 않도록 여기서 던지고,
        //   가지고 있던 주문으로 slack 알림을 보낸다.
        Order current;
        try {
            current = transactionTemplate.execute( // 주문 재조회
                    status -> orderRepository
                            .findByIdOrThrow(order.getId()));
        } catch (RuntimeException e) {

            log.error("[주문 생성] {} 중 상태 전이 거절, 현재 주문 재조회 실패 orderId={} → 자동 보상 안 함",
                    step, order.getId(), e);

            if (compensationStatus == CompensationStatus.PENDING) {
                recordManualAction(order,
                        "주문 생성 중 상태 전이 거절 (" + step + "), 현재 상태 조회 실패: 주문, 재고, 배송 확인 필요");
            }
            return;
        }

        // 버그 추적용 에러 로그
        log.error("[주문 생성] {} 중 상태 전이 거절 orderId={}, 현재 status={}, 보상={} → 자동 보상 안 함",
                step, order.getId(), current.getStatus(), current.getCompensationStatus());

        // 다른 서비스를 이미 건드려 보상이 필할 수 있는 단계 (재고 차감, 배송 생성) -> 자동 보상 불가하므로 운영자 확인
        if (compensationStatus == CompensationStatus.PENDING) {
            recordManualAction(current, "주문 생성 중 상태 전이 거절 (" + step + "): 재고, 배송 확인 필요");
        }
        // NOT_REQUIRED 의 경우 -> 수동 처리 필요 x 로그만 기록
    }

    /**
     * 수동 처리 알림 이벤트를 별도 트랜잭션에서 Outbox에 저장한다.
     * 주문 상태는 변경하지 않는다.
     * 저장 실패 시(DB 장애 등) 로그만 남기고 예외를 전파하지 않는다.
     *
     * @param order  알림 대상 주문 엔티티
     * @param reason 수동 처리가 필요한 사유
     */
    private void recordManualAction(Order order, String reason) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    outboxRecorder.record(OrderEvent.manualActionRequired(order, reason)));
        } catch (RuntimeException e) {
            log.error("[수동 처리 필요] 알림 이벤트 저장 실패 orderId={}, 사유={}", order.getId(), reason, e);
        }
    }

    /**
     * 주문 생성 1,2 단계 에서 조회한 상품 및 업체 정보를 묶어 전달한다.
     *
     * @param product   상품 조회 응답 DTO
     * @param supplier  공급 업체 조회 응답 DTO
     * @param requester 요청 업체 조회 응답 DTO
     */
    private record OrderSource(ProductResponse product, CompanyResponse supplier, CompanyResponse requester) {
    }
}
