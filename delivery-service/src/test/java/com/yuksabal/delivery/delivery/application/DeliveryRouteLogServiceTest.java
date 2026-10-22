package com.yuksabal.delivery.delivery.application;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.delivery.delivery.domain.Delivery;
import com.yuksabal.delivery.delivery.domain.DeliveryStatus;
import com.yuksabal.delivery.delivery.domain.DeliveryRepository;
import com.yuksabal.delivery.delivery.presentation.dto.request.RouteLogStatusUpdateRequest;
import com.yuksabal.delivery.delivery.presentation.dto.response.DeliveryRouteLogResponse;
import com.yuksabal.delivery.delivery.domain.DeliveryRouteLog;
import com.yuksabal.delivery.delivery.domain.RouteStatus;
import com.yuksabal.delivery.delivery.domain.DeliveryRouteLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_ACCESS_DENIED;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;

// Repository·Feign은 가짜로 두고, 응답(성공·예외)과 결과 상태(배송·경로 기록)만 확인한다
// 호출 여부는 검증하지 않는다: 애그리거트 전환 후에도 그대로 통과해야 하는 현재 동작 고정용
// 상태는 reflection으로 만든다. 상태 전이 메서드를 픽스처에서 쓰지 않기 위해서
@ExtendWith(MockitoExtension.class)
class DeliveryRouteLogServiceTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID OTHER_DELIVERY_ID = UUID.randomUUID();
    private static final UUID ROUTE_LOG_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID DELIVERY_MANAGER_ID = UUID.randomUUID();
    private static final UUID ROUTE_MANAGER_ID = UUID.randomUUID();
    private static final UUID NEW_MANAGER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final BigDecimal DISTANCE_KM = new BigDecimal("325.50");

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private DeliveryRouteLogRepository deliveryRouteLogRepository;
    @Mock
    private DeliveryManagerValidator deliveryManagerValidator;
    @Mock
    private DeliveryReadPolicy deliveryReadPolicy;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DeliveryRouteLogService deliveryRouteLogService;

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
    class 경로_출발 {

        @Test
        void 경로_담당자가_출발하면_경로_기록과_배송이_IN_TRANSIT이_된다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            DeliveryRouteLogResponse response = deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), ROUTE_MANAGER_ID, UserRole.DELIVERY);

            assertThat(response.routeStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(response.departedAt()).isNotNull();
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getDepartedAt()).isNotNull();
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @Test
        void MASTER는_담당자가_아니어도_출발시킨다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            DeliveryRouteLogResponse response = deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), MASTER_ID, UserRole.MASTER);

            assertThat(response.routeStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @Test
        void 배송이_없으면_404() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

            assertNotFound(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), ROUTE_MANAGER_ID, UserRole.DELIVERY), "deliveryId");
        }

        @Test
        void 경로_기록이_없으면_404() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.READY)));
            given(deliveryRouteLogRepository.findById(ROUTE_LOG_ID)).willReturn(Optional.empty());

            assertNotFound(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), ROUTE_MANAGER_ID, UserRole.DELIVERY), "routeLogId");
        }

        @Test
        void 다른_배송의_경로_기록이면_404이고_두_상태가_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            Delivery other = deliveryIn(OTHER_DELIVERY_ID, DeliveryStatus.READY);
            DeliveryRouteLog otherRouteLog = routeLogIn(other, RouteStatus.READY);
            stubFound(delivery, otherRouteLog);

            assertNotFound(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), MASTER_ID, UserRole.MASTER), "routeLogId");

            assertThat(otherRouteLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(other.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        // 권한이 경로 기록의 담당자에 달려 있어 경로 기록을 먼저 찾는다 (404 → 403 → 409)
        @Test
        void 없는_경로_기록이면_권한이_없는_역할도_403보다_404가_먼저다() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.READY)));
            given(deliveryRouteLogRepository.findById(ROUTE_LOG_ID)).willReturn(Optional.empty());

            assertNotFound(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), OTHER_ID, UserRole.USER), "routeLogId");
        }

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"USER", "COMPANY"})
        void USER와_COMPANY는_403이고_두_상태가_그대로다(UserRole role) {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            assertAccessDenied(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), ROUTE_MANAGER_ID, role));

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @Test
        void 경로_담당자가_아닌_DELIVERY는_403이고_두_상태가_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            assertAccessDenied(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), OTHER_ID, UserRole.DELIVERY));

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        // 배송(허브→업체) 담당자는 경로 기록 상태를 바꿀 수 없다 (배송 상태 변경과 반대)
        @Test
        void 배송_담당자는_경로_담당자가_아니면_403이고_두_상태가_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            assertAccessDenied(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), DELIVERY_MANAGER_ID, UserRole.DELIVERY));

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @Test
        void 권한이_없으면_잘못된_전이여도_409보다_403이_먼저다() {
            Delivery delivery = deliveryIn(DeliveryStatus.ARRIVED_AT_HUB);
            stubFound(delivery, routeLogIn(delivery, RouteStatus.ARRIVED));

            assertAccessDenied(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), OTHER_ID, UserRole.DELIVERY));
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"IN_TRANSIT", "ARRIVED", "CANCELLED"})
        void 경로_기록이_READY가_아니면_409이고_두_상태가_그대로다(RouteStatus current) {
            DeliveryStatus deliveryStatus = matchingDeliveryStatus(current);
            Delivery delivery = deliveryIn(deliveryStatus);
            DeliveryRouteLog routeLog = routeLogIn(delivery, current);
            stubFound(delivery, routeLog);

            assertInvalidTransition(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, depart(), ROUTE_MANAGER_ID, UserRole.DELIVERY),
                    "READY 상태에서만 허브를 출발할 수 있습니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(current);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(deliveryStatus);
        }
    }

    @Nested
    class 경로_도착 {

        @Test
        void 도착하면_실제_거리를_기록하고_배송은_ARRIVED_AT_HUB가_된다() {
            Delivery delivery = deliveryIn(DeliveryStatus.IN_TRANSIT);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.IN_TRANSIT);
            stubFound(delivery, routeLog);

            DeliveryRouteLogResponse response = deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, arrive(DISTANCE_KM), ROUTE_MANAGER_ID, UserRole.DELIVERY);

            assertThat(response.routeStatus()).isEqualTo(RouteStatus.ARRIVED);
            assertThat(response.actualDistanceKm()).isEqualByComparingTo(DISTANCE_KM);
            assertThat(response.arrivedAt()).isNotNull();
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.ARRIVED);
            assertThat(routeLog.getActualDurationMinutes()).isNotNull();
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.ARRIVED_AT_HUB);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "-1.5"})
        void 실제_거리가_없거나_0_이하면_400이고_두_상태가_그대로다(BigDecimal actualDistanceKm) {
            Delivery delivery = deliveryIn(DeliveryStatus.IN_TRANSIT);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.IN_TRANSIT);
            stubFound(delivery, routeLog);

            assertThatThrownBy(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, arrive(actualDistanceKm), ROUTE_MANAGER_ID, UserRole.DELIVERY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly("actualDistanceKm");
                    });

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getArrivedAt()).isNull();
            assertThat(routeLog.getActualDistanceKm()).isNull();
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @Test
        void IN_TRANSIT이_아니면_거리가_없어도_400보다_409가_먼저다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            assertInvalidTransition(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, arrive(null), ROUTE_MANAGER_ID, UserRole.DELIVERY),
                    "이동 중(IN_TRANSIT)인 경로 기록만 도착 처리할 수 있습니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"READY", "ARRIVED", "CANCELLED"})
        void IN_TRANSIT이_아니면_409이고_두_상태가_그대로다(RouteStatus current) {
            DeliveryStatus deliveryStatus = matchingDeliveryStatus(current);
            Delivery delivery = deliveryIn(deliveryStatus);
            DeliveryRouteLog routeLog = routeLogIn(delivery, current);
            stubFound(delivery, routeLog);

            assertInvalidTransition(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, arrive(DISTANCE_KM), ROUTE_MANAGER_ID, UserRole.DELIVERY),
                    "이동 중(IN_TRANSIT)인 경로 기록만 도착 처리할 수 있습니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(current);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(deliveryStatus);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"READY", "CANCELLED"})
        void 이_API로_바꿀_수_없는_상태를_요청하면_409이고_두_상태가_그대로다(RouteStatus target) {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            assertInvalidTransition(() -> deliveryRouteLogService.changeRouteStatus(
                    DELIVERY_ID, ROUTE_LOG_ID, new RouteLogStatusUpdateRequest(target, DISTANCE_KM),
                    ROUTE_MANAGER_ID, UserRole.DELIVERY),
                    "이 API로 변경할 수 없는 상태입니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }
    }

    @Nested
    class 경로_담당자_지정 {

        @ParameterizedTest
        @EnumSource(value = UserRole.class, mode = EnumSource.Mode.EXCLUDE, names = "MASTER")
        void MASTER가_아니면_403이고_담당자가_그대로다(UserRole role) {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            lenient().when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
            lenient().when(deliveryRouteLogRepository.findById(ROUTE_LOG_ID)).thenReturn(Optional.of(routeLog));

            assertThatThrownBy(() -> deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, role))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(ROUTE_MANAGER_ID);
        }

        @Test
        void 배송이_없으면_404() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

            assertNotFound(() -> deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, UserRole.MASTER), "deliveryId");
        }

        @Test
        void 경로_기록이_없으면_404() {
            given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(deliveryIn(DeliveryStatus.READY)));
            given(deliveryRouteLogRepository.findById(ROUTE_LOG_ID)).willReturn(Optional.empty());

            assertNotFound(() -> deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, UserRole.MASTER), "routeLogId");
        }

        @Test
        void 다른_배송의_경로_기록이면_404이고_담당자가_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog otherRouteLog = routeLogIn(deliveryIn(OTHER_DELIVERY_ID, DeliveryStatus.READY), RouteStatus.READY);
            stubFound(delivery, otherRouteLog);

            assertNotFound(() -> deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, UserRole.MASTER), "routeLogId");

            assertThat(otherRouteLog.getDeliveryManagerId()).isEqualTo(ROUTE_MANAGER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"IN_TRANSIT", "ARRIVED", "CANCELLED"})
        void 허브를_출발했거나_끝난_경로_기록은_409이고_담당자가_그대로다(RouteStatus current) {
            Delivery delivery = deliveryIn(matchingDeliveryStatus(current));
            DeliveryRouteLog routeLog = routeLogIn(delivery, current);
            stubFound(delivery, routeLog);

            assertInvalidTransition(() -> deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, UserRole.MASTER),
                    "허브를 출발했거나 끝난 경로 기록은 담당자를 지정할 수 없습니다.");

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(ROUTE_MANAGER_ID);
        }

        @Test
        void user_확인에_실패하면_그대로_전달하고_담당자가_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);
            BusinessException inactive = new BusinessException(BUSINESS_RULE_VIOLATION,
                    "비활성화된 회원은 배송 담당자로 지정할 수 없습니다.", "deliveryManagerId");
            willThrow(inactive).given(deliveryManagerValidator).validate(NEW_MANAGER_ID);

            assertThatThrownBy(() -> deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, UserRole.MASTER))
                    .isSameAs(inactive);

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(ROUTE_MANAGER_ID);
        }

        @Test
        void READY면_담당자를_지정한다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);

            DeliveryRouteLogResponse response = deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, UserRole.MASTER);

            assertThat(response.deliveryManagerId()).isEqualTo(NEW_MANAGER_ID);
            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(NEW_MANAGER_ID);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @Test
        void user_확인_중에_허브를_출발했으면_다시_확인해_409() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);
            stubFound(delivery, routeLog);
            // user 확인 중에 다른 요청이 경로 기록을 출발시킨 상황
            willAnswer(inv -> {
                setStatus(delivery, DeliveryStatus.IN_TRANSIT);
                setStatus(routeLog, RouteStatus.IN_TRANSIT);
                return null;
            }).given(deliveryManagerValidator).validate(NEW_MANAGER_ID);

            assertInvalidTransition(() -> deliveryRouteLogService.assignRouteManager(
                    DELIVERY_ID, ROUTE_LOG_ID, NEW_MANAGER_ID, UserRole.MASTER),
                    "허브를 출발했거나 끝난 경로 기록은 담당자를 지정할 수 없습니다.");

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(ROUTE_MANAGER_ID);
        }
    }

    private void stubFound(Delivery delivery, DeliveryRouteLog routeLog) {
        given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));
        given(deliveryRouteLogRepository.findById(ROUTE_LOG_ID)).willReturn(Optional.of(routeLog));
    }

    private static RouteLogStatusUpdateRequest depart() {
        return new RouteLogStatusUpdateRequest(RouteStatus.IN_TRANSIT, null);
    }

    private static RouteLogStatusUpdateRequest arrive(BigDecimal actualDistanceKm) {
        return new RouteLogStatusUpdateRequest(RouteStatus.ARRIVED, actualDistanceKm);
    }

    private static void assertNotFound(Runnable action, String field) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                    assertThat(e.getDetails()).extracting("field").containsExactly(field);
                });
    }

    private static void assertAccessDenied(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DELIVERY_ACCESS_DENIED));
    }

    private static void assertInvalidTransition(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    // 경로 기록 상태와 짝이 맞는 배송 상태 (READY↔READY, IN_TRANSIT↔IN_TRANSIT, ARRIVED↔ARRIVED_AT_HUB, CANCELLED↔CANCELLED)
    private static DeliveryStatus matchingDeliveryStatus(RouteStatus routeStatus) {
        return switch (routeStatus) {
            case READY -> DeliveryStatus.READY;
            case IN_TRANSIT -> DeliveryStatus.IN_TRANSIT;
            case ARRIVED -> DeliveryStatus.ARRIVED_AT_HUB;
            case CANCELLED -> DeliveryStatus.CANCELLED;
        };
    }

    private static Delivery deliveryIn(DeliveryStatus status) {
        return deliveryIn(DELIVERY_ID, status);
    }

    // 다른 허브 배송
    private static Delivery deliveryIn(UUID deliveryId, DeliveryStatus status) {
        Delivery delivery = Delivery.builder()
                .orderId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .destinationHubId(UUID.randomUUID())
                .destinationCompanyId(UUID.randomUUID())
                .deliveryManagerId(DELIVERY_MANAGER_ID)
                .ordererId(UUID.randomUUID())
                .address("부산시 해운대구")
                .recipientName("수령인")
                .build();
        ReflectionTestUtils.setField(delivery, "id", deliveryId);
        setStatus(delivery, status);
        return delivery;
    }

    private static DeliveryRouteLog routeLogIn(Delivery delivery, RouteStatus status) {
        DeliveryRouteLog routeLog = DeliveryRouteLog.builder()
                .delivery(delivery)
                .hubRouteId(UUID.randomUUID())
                .deliveryManagerId(ROUTE_MANAGER_ID)
                .expectedDistanceKm(new BigDecimal("320.00"))
                .expectedDurationMinutes(240)
                .build();
        ReflectionTestUtils.setField(routeLog, "id", ROUTE_LOG_ID);
        setStatus(routeLog, status);
        return routeLog;
    }

    private static void setStatus(Delivery delivery, DeliveryStatus status) {
        ReflectionTestUtils.setField(delivery, "deliveryStatus", status);
    }

    // 출발한 경로 기록은 출발 시각이 있어야 도착 시 소요 시간을 계산할 수 있다
    private static void setStatus(DeliveryRouteLog routeLog, RouteStatus status) {
        ReflectionTestUtils.setField(routeLog, "routeStatus", status);
        if (status == RouteStatus.IN_TRANSIT || status == RouteStatus.ARRIVED) {
            ReflectionTestUtils.setField(routeLog, "departedAt", LocalDateTime.now().minusMinutes(90));
        }
    }
}
