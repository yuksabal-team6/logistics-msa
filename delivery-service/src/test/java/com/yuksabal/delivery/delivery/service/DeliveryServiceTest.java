package com.yuksabal.delivery.delivery.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.delivery.delivery.client.HubClient;
import com.yuksabal.delivery.delivery.client.OrderClient;
import com.yuksabal.delivery.delivery.dto.response.DeliveryResponse;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.entity.DeliveryStatus;
import com.yuksabal.delivery.delivery.repository.DeliveryRepository;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.repository.DeliveryRouteLogRepository;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_ACCESS_DENIED;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Repository·Feign·조회 권한은 가짜로 두고, 검증 순서와 분기만 확인한다
// 트랜잭션 롤백·@Version 충돌의 실제 동작은 여기서 확인하지 않는다 (2단계)
@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID MANAGER_ID = UUID.randomUUID();
    private static final UUID NEW_MANAGER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private DeliveryRouteLogRepository deliveryRouteLogRepository;
    @Mock
    private HubClient hubClient;
    @Mock
    private OrderClient orderClient;
    @Mock
    private DeliveryManagerValidator deliveryManagerValidator;
    @Mock
    private DeliveryReadPolicy deliveryReadPolicy;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        // 트랜잭션 없이 콜백만 바로 실행한다. 권한(403)에서 끝나는 테스트는 부르지 않아 lenient
        lenient().doAnswer(inv -> {
            TransactionCallback<?> action = inv.getArgument(0);
            return action.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Nested
    class 배송_상태_변경 {

        @Test
        void 배송이_없으면_404이고_order를_부르지_않는다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.DELIVERING, MANAGER_ID, UserRole.DELIVERY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("deliveryId");
                    });

            verifyNoInteractions(orderClient);
        }

        // 권한이 배송의 담당자에 달려 있어 배송을 먼저 찾는다 (404 → 403 → 409)
        @Test
        void 없는_배송이면_권한이_없는_역할도_403보다_404가_먼저다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.DELIVERING, OTHER_ID, UserRole.USER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND));
        }

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"USER", "COMPANY"})
        void USER와_COMPANY는_403이고_상태가_그대로다(UserRole role) {
            Delivery delivery = deliveryIn(DeliveryStatus.ARRIVED_AT_HUB);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));

            assertAccessDenied(() -> deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.DELIVERING, MANAGER_ID, role));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.ARRIVED_AT_HUB);
        }

        @Test
        void 담당자가_아닌_DELIVERY는_403이고_상태가_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.ARRIVED_AT_HUB);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));

            assertAccessDenied(() -> deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.DELIVERING, OTHER_ID, UserRole.DELIVERY));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.ARRIVED_AT_HUB);
        }

        @Test
        void 권한이_없으면_잘못된_전이여도_409보다_403이_먼저다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.READY)));

            assertAccessDenied(() -> deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.COMPLETED, OTHER_ID, UserRole.DELIVERY));
        }

        @Test
        void 담당자라도_잘못된_전이면_409이고_order를_부르지_않는다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.READY)));

            assertThatThrownBy(() -> deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.COMPLETED, MANAGER_ID, UserRole.DELIVERY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION));

            verifyNoInteractions(orderClient);
        }

        @Test
        void MASTER는_담당자가_아니어도_변경한다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.ARRIVED_AT_HUB)));

            DeliveryResponse response = deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.DELIVERING, MASTER_ID, UserRole.MASTER);

            assertThat(response.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERING);
        }

        @Test
        void 배송_시작은_order를_부르지_않는다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.ARRIVED_AT_HUB)));

            DeliveryResponse response = deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.DELIVERING, MANAGER_ID, UserRole.DELIVERY);

            assertThat(response.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERING);
            verifyNoInteractions(orderClient);
        }

        @Test
        void 배송을_완료하면_order에_주문_완료를_알린다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.DELIVERING)));

            DeliveryResponse response = deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.COMPLETED, MANAGER_ID, UserRole.DELIVERY);

            assertThat(response.deliveryStatus()).isEqualTo(DeliveryStatus.COMPLETED);
            verify(orderClient).completeOrder(ORDER_ID);
        }

        @ParameterizedTest
        @MethodSource("com.yuksabal.delivery.delivery.service.DeliveryServiceTest#orderFailures")
        void 주문_완료_호출이_실패해도_배송_완료는_성공한다(RuntimeException failure) {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.DELIVERING)));
            willThrow(failure).given(orderClient).completeOrder(ORDER_ID);

            DeliveryResponse response = deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.COMPLETED, MANAGER_ID, UserRole.DELIVERY);

            assertThat(response.deliveryStatus()).isEqualTo(DeliveryStatus.COMPLETED);
            assertThat(response.completedAt()).isNotNull();
            verify(orderClient).completeOrder(ORDER_ID);
        }

        @Test
        void 커밋이_실패하면_order를_부르지_않는다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.DELIVERING)));
            // 콜백은 끝났지만 커밋 시 @Version 충돌이 난 상황
            ObjectOptimisticLockingFailureException conflict =
                    new ObjectOptimisticLockingFailureException(Delivery.class, DELIVERY_ID);
            doAnswer(inv -> {
                TransactionCallback<?> action = inv.getArgument(0);
                action.doInTransaction(null);
                throw conflict;
            }).when(transactionTemplate).execute(any());

            assertThatThrownBy(() -> deliveryService.changeDeliveryStatus(
                    DELIVERY_ID, DeliveryStatus.COMPLETED, MANAGER_ID, UserRole.DELIVERY))
                    .isSameAs(conflict);

            verifyNoInteractions(orderClient);
        }
    }

    @Nested
    class 담당자_지정 {

        @ParameterizedTest
        @EnumSource(value = UserRole.class, mode = EnumSource.Mode.EXCLUDE, names = "MASTER")
        void MASTER가_아니면_403이고_아무것도_확인하지_않는다(UserRole role) {
            assertThatThrownBy(() -> deliveryService.assignDeliveryManager(DELIVERY_ID, NEW_MANAGER_ID, role))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(deliveryRepository, deliveryManagerValidator, transactionTemplate);
        }

        @Test
        void 배송이_없으면_404이고_user를_확인하지_않는다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> deliveryService.assignDeliveryManager(DELIVERY_ID, NEW_MANAGER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND));

            verifyNoInteractions(deliveryManagerValidator);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"DELIVERING", "COMPLETED", "CANCELLED"})
        void 지정할_수_없는_상태면_409이고_user를_확인하지_않는다(DeliveryStatus status) {
            Delivery delivery = deliveryIn(status);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));

            assertThatThrownBy(() -> deliveryService.assignDeliveryManager(DELIVERY_ID, NEW_MANAGER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION));

            verifyNoInteractions(deliveryManagerValidator);
            assertThat(delivery.getDeliveryManagerId()).isEqualTo(MANAGER_ID);
        }

        @Test
        void user_확인에_실패하면_그대로_전달하고_담당자가_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));
            BusinessException inactive = new BusinessException(BUSINESS_RULE_VIOLATION,
                    "비활성화된 회원은 배송 담당자로 지정할 수 없습니다.", "deliveryManagerId");
            willThrow(inactive).given(deliveryManagerValidator).validate(NEW_MANAGER_ID);

            assertThatThrownBy(() -> deliveryService.assignDeliveryManager(DELIVERY_ID, NEW_MANAGER_ID, UserRole.MASTER))
                    .isSameAs(inactive);

            verify(deliveryRepository, times(1)).findById(DELIVERY_ID);
            assertThat(delivery.getDeliveryManagerId()).isEqualTo(MANAGER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"READY", "IN_TRANSIT", "ARRIVED_AT_HUB"})
        void 업체로_출발하기_전이면_담당자를_지정한다(DeliveryStatus status) {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(status)));

            DeliveryResponse response = deliveryService.assignDeliveryManager(DELIVERY_ID, NEW_MANAGER_ID, UserRole.MASTER);

            verify(deliveryManagerValidator).validate(NEW_MANAGER_ID);
            assertThat(response.deliveryManagerId()).isEqualTo(NEW_MANAGER_ID);
        }

        @Test
        void user_확인_중에_업체로_출발했으면_다시_확인해_409() {
            Delivery delivery = deliveryIn(DeliveryStatus.ARRIVED_AT_HUB);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));
            // user 확인 중에 다른 요청이 배송을 시작한 상황
            willAnswer(inv -> {
                delivery.changeStatus(DeliveryStatus.DELIVERING);
                return null;
            }).given(deliveryManagerValidator).validate(NEW_MANAGER_ID);

            assertThatThrownBy(() -> deliveryService.assignDeliveryManager(DELIVERY_ID, NEW_MANAGER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION));

            assertThat(delivery.getDeliveryManagerId()).isEqualTo(MANAGER_ID);
        }
    }

    @Nested
    class 배송_삭제 {

        @ParameterizedTest
        @EnumSource(value = UserRole.class, mode = EnumSource.Mode.EXCLUDE, names = "MASTER")
        void MASTER가_아니면_403이고_아무것도_확인하지_않는다(UserRole role) {
            assertThatThrownBy(() -> deliveryService.deleteDelivery(DELIVERY_ID, OTHER_ID, role))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(deliveryRepository, deliveryRouteLogRepository);
        }

        @Test
        void 배송이_없으면_404() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> deliveryService.deleteDelivery(DELIVERY_ID, MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND));

            verifyNoInteractions(deliveryRouteLogRepository);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"READY", "IN_TRANSIT", "ARRIVED_AT_HUB", "DELIVERING"})
        void 진행_중인_배송은_409이고_경로_기록을_건드리지_않는다(DeliveryStatus status) {
            Delivery delivery = deliveryIn(status);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));

            assertThatThrownBy(() -> deliveryService.deleteDelivery(DELIVERY_ID, MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION));

            verifyNoInteractions(deliveryRouteLogRepository);
            assertThat(delivery.getDeletedAt()).isNull();
        }

        @Test
        void 끝난_배송은_경로_기록과_함께_요청자_기준으로_삭제한다() {
            Delivery delivery = deliveryIn(DeliveryStatus.COMPLETED);
            DeliveryRouteLog routeLog = routeLog(delivery);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));
            given(deliveryRouteLogRepository.findByDeliveryId(DELIVERY_ID)).willReturn(Optional.of(routeLog));

            deliveryService.deleteDelivery(DELIVERY_ID, MASTER_ID, UserRole.MASTER);

            assertThat(delivery.getDeletedAt()).isNotNull();
            assertThat(delivery.getDeletedBy()).isEqualTo(MASTER_ID);
            assertThat(routeLog.getDeletedAt()).isNotNull();
            assertThat(routeLog.getDeletedBy()).isEqualTo(MASTER_ID);
        }

        @Test
        void 경로_기록이_없는_배송도_삭제한다() {
            Delivery delivery = deliveryIn(DeliveryStatus.CANCELLED);
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));
            given(deliveryRouteLogRepository.findByDeliveryId(DELIVERY_ID)).willReturn(Optional.empty());

            deliveryService.deleteDelivery(DELIVERY_ID, MASTER_ID, UserRole.MASTER);

            assertThat(delivery.getDeletedAt()).isNotNull();
            assertThat(delivery.getDeletedBy()).isEqualTo(MASTER_ID);
        }
    }

    // notifyOrderCompleted가 잡는 예외 종류마다 하나씩
    static Stream<RuntimeException> orderFailures() {
        Request request = Request.create(Request.HttpMethod.POST, "/internal/orders/" + ORDER_ID + "/complete",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return Stream.of(
                new FeignClientException(409, "INVALID_STATUS_TRANSITION", "CONFIRMED 상태의 주문만 완료할 수 있습니다.", List.of()),
                new FeignServerException(503, "SERVICE_UNAVAILABLE", "order-service 없음", "OrderClient#completeOrder"),
                new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request),
                new IllegalStateException("예상 못 한 예외"));
    }

    private static void assertAccessDenied(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DELIVERY_ACCESS_DENIED));
    }

    // 다른 허브 배송: READY → IN_TRANSIT → ARRIVED_AT_HUB → DELIVERING → COMPLETED, READY → CANCELLED
    private static Delivery deliveryIn(DeliveryStatus status) {
        Delivery delivery = Delivery.builder()
                .orderId(ORDER_ID)
                .departureHubId(UUID.randomUUID())
                .destinationHubId(UUID.randomUUID())
                .destinationCompanyId(UUID.randomUUID())
                .deliveryManagerId(MANAGER_ID)
                .ordererId(UUID.randomUUID())
                .address("부산시 해운대구")
                .recipientName("수령인")
                .build();
        ReflectionTestUtils.setField(delivery, "id", DELIVERY_ID);
        switch (status) {
            case READY -> { }
            case CANCELLED -> delivery.cancel();
            default -> {
                delivery.departHub();
                if (status == DeliveryStatus.IN_TRANSIT) break;
                delivery.arriveAtHub();
                if (status == DeliveryStatus.ARRIVED_AT_HUB) break;
                delivery.changeStatus(DeliveryStatus.DELIVERING);
                if (status == DeliveryStatus.DELIVERING) break;
                delivery.changeStatus(DeliveryStatus.COMPLETED);
            }
        }
        return delivery;
    }

    private static DeliveryRouteLog routeLog(Delivery delivery) {
        return DeliveryRouteLog.builder()
                .delivery(delivery)
                .hubRouteId(UUID.randomUUID())
                .expectedDistanceKm(new BigDecimal("320.00"))
                .expectedDurationMinutes(240)
                .build();
    }
}
