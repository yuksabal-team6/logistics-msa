package com.yuksabal.delivery.delivery.domain;

import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_ALREADY_COMPLETED;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 배송 애그리거트의 상태 전이·담당자 지정·취소·삭제 규칙과 경로 기록 연동
// 경로 기록 연동이 필요한 상태(IN_TRANSIT·ARRIVED_AT_HUB·CANCELLED)는 reflection으로 정한다
class DeliveryTest {

    private static final UUID SEOUL_HUB_ID = UUID.randomUUID();
    private static final UUID BUSAN_HUB_ID = UUID.randomUUID();
    private static final UUID ORDERER_ID = UUID.randomUUID();
    private static final UUID MANAGER_ID = UUID.randomUUID();
    private static final UUID ROUTE_MANAGER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final BigDecimal DISTANCE_KM = new BigDecimal("325.50");
    private static final String DELIVERY_CANCEL_MESSAGE = "허브 출발 후에는 배송을 취소할 수 없습니다.";
    private static final String ROUTE_LOG_CANCEL_MESSAGE = "허브 출발 후에는 배송 경로 기록을 취소할 수 없습니다.";

    @Nested
    class 상태_변경 {

        @Test
        void 같은_허브는_READY에서_배송을_시작한다() {
            Delivery delivery = delivery(true);

            delivery.changeStatus(DeliveryStatus.DELIVERING);

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERING);
            assertThat(delivery.getStartedAt()).isNotNull();
        }

        @Test
        void 다른_허브는_ARRIVED_AT_HUB에서_배송을_시작한다() {
            Delivery delivery = deliveryIn(DeliveryStatus.ARRIVED_AT_HUB);

            delivery.changeStatus(DeliveryStatus.DELIVERING);

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERING);
            assertThat(delivery.getStartedAt()).isNotNull();
        }

        @Test
        void 다른_허브가_READY에서_배송을_시작하면_409() {
            Delivery delivery = delivery(false);

            assertInvalidTransition(() -> delivery.changeStatus(DeliveryStatus.DELIVERING));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
            assertThat(delivery.getStartedAt()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"IN_TRANSIT", "DELIVERING", "COMPLETED", "CANCELLED"})
        void 다른_허브가_ARRIVED_AT_HUB가_아니면_배송을_시작할_수_없다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            assertInvalidTransition(() -> delivery.changeStatus(DeliveryStatus.DELIVERING));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(current);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"DELIVERING", "COMPLETED", "CANCELLED"})
        void 같은_허브가_READY가_아니면_배송을_시작할_수_없다(DeliveryStatus current) {
            Delivery delivery = sameHubDeliveryIn(current);

            assertInvalidTransition(() -> delivery.changeStatus(DeliveryStatus.DELIVERING));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(current);
        }

        @Test
        void DELIVERING에서_배송을_완료한다() {
            Delivery delivery = deliveryIn(DeliveryStatus.DELIVERING);

            delivery.changeStatus(DeliveryStatus.COMPLETED);

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.COMPLETED);
            assertThat(delivery.getCompletedAt()).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "DELIVERING")
        void DELIVERING이_아니면_완료할_수_없다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            assertInvalidTransition(() -> delivery.changeStatus(DeliveryStatus.COMPLETED));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(current);
            if (current != DeliveryStatus.COMPLETED) {
                assertThat(delivery.getCompletedAt()).isNull();
            }
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"READY", "IN_TRANSIT", "ARRIVED_AT_HUB", "CANCELLED"})
        void 이_API로_바꿀_수_없는_상태를_요청하면_409(DeliveryStatus target) {
            Delivery delivery = delivery(false);

            assertInvalidTransition(() -> delivery.changeStatus(target));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }
    }

    @Nested
    class 경로_출발 {

        @Test
        void READY에서_출발하면_경로_기록과_배송이_IN_TRANSIT이_된다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

            delivery.changeRouteStatus(routeLog, RouteStatus.IN_TRANSIT, null);

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getDepartedAt()).isNotNull();
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"IN_TRANSIT", "ARRIVED", "CANCELLED"})
        void 경로_기록이_READY가_아니면_409이고_둘_다_그대로다(RouteStatus current) {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery, current);

            assertInvalidTransition(() -> delivery.changeRouteStatus(routeLog, RouteStatus.IN_TRANSIT, null),
                    "READY 상태에서만 허브를 출발할 수 있습니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(current);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @Test
        void 배송이_READY가_아니면_409이고_경로_기록은_READY로_남는다() {
            Delivery delivery = deliveryIn(DeliveryStatus.CANCELLED);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

            assertInvalidTransition(() -> delivery.changeRouteStatus(routeLog, RouteStatus.IN_TRANSIT, null),
                    "READY 상태의 배송만 허브에서 출발할 수 있습니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(routeLog.getDepartedAt()).isNull();
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        }
    }

    @Nested
    class 경로_도착 {

        @Test
        void IN_TRANSIT에서_도착하면_실제_거리를_기록하고_배송은_ARRIVED_AT_HUB가_된다() {
            Delivery delivery = deliveryIn(DeliveryStatus.IN_TRANSIT);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.IN_TRANSIT);

            delivery.changeRouteStatus(routeLog, RouteStatus.ARRIVED, DISTANCE_KM);

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.ARRIVED);
            assertThat(routeLog.getArrivedAt()).isNotNull();
            assertThat(routeLog.getActualDistanceKm()).isEqualByComparingTo(DISTANCE_KM);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.ARRIVED_AT_HUB);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "-1.5"})
        void 실제_거리가_없거나_0_이하면_400이고_둘_다_그대로다(BigDecimal actualDistanceKm) {
            Delivery delivery = deliveryIn(DeliveryStatus.IN_TRANSIT);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.IN_TRANSIT);

            assertThatThrownBy(() -> delivery.changeRouteStatus(routeLog, RouteStatus.ARRIVED, actualDistanceKm))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly("actualDistanceKm");
                    });

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getActualDistanceKm()).isNull();
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @Test
        void 경로_기록이_IN_TRANSIT이_아니면_거리가_없어도_400보다_409가_먼저다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

            assertInvalidTransition(() -> delivery.changeRouteStatus(routeLog, RouteStatus.ARRIVED, null),
                    "이동 중(IN_TRANSIT)인 경로 기록만 도착 처리할 수 있습니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        // 거리 검증이 배송 상태 검사보다 먼저다 (경로 기록 409 → 거리 400 → 배송 409)
        @Test
        void 배송이_IN_TRANSIT이_아니면_409이고_경로_기록은_IN_TRANSIT으로_남는다() {
            Delivery delivery = deliveryIn(DeliveryStatus.READY);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.IN_TRANSIT);

            assertInvalidTransition(() -> delivery.changeRouteStatus(routeLog, RouteStatus.ARRIVED, DISTANCE_KM),
                    "이동 중(IN_TRANSIT)인 배송만 허브에 도착할 수 있습니다.");

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getArrivedAt()).isNull();
            assertThat(routeLog.getActualDistanceKm()).isNull();
            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RouteStatus.class, names = {"READY", "CANCELLED"})
    void 경로_기록에_이_API로_바꿀_수_없는_상태를_요청하면_409(RouteStatus target) {
        Delivery delivery = delivery(false);
        DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

        assertInvalidTransition(() -> delivery.changeRouteStatus(routeLog, target, DISTANCE_KM),
                "이 API로 변경할 수 없는 상태입니다.");

        assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
        assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Nested
    class 담당자_지정 {

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"READY", "IN_TRANSIT", "ARRIVED_AT_HUB"})
        void 업체로_출발하기_전에는_지정할_수_있다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            delivery.assignManager(OTHER_ID);

            assertThat(delivery.getDeliveryManagerId()).isEqualTo(OTHER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"DELIVERING", "COMPLETED", "CANCELLED"})
        void 업체로_출발했거나_끝난_배송은_409이고_담당자가_그대로다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            assertInvalidTransition(() -> delivery.assignManager(OTHER_ID));

            assertThat(delivery.getDeliveryManagerId()).isEqualTo(MANAGER_ID);
        }
    }

    @Nested
    class 경로_담당자_지정 {

        @Test
        void 경로_기록이_READY면_지정한다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

            delivery.assignRouteManager(routeLog, OTHER_ID);

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(OTHER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"IN_TRANSIT", "ARRIVED", "CANCELLED"})
        void 허브를_출발했거나_끝난_경로_기록은_409이고_담당자가_그대로다(RouteStatus current) {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery, current);

            assertInvalidTransition(() -> delivery.assignRouteManager(routeLog, OTHER_ID));

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(ROUTE_MANAGER_ID);
        }
    }

    @Nested
    class 취소_경로_기록_없음 {

        @Test
        void READY면_취소한다() {
            Delivery delivery = delivery(false);

            delivery.cancel(Optional.empty());

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            assertThat(delivery.isCancelled()).isTrue();
        }

        @Test
        void 이미_취소된_배송이면_예외_없이_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.CANCELLED);

            assertThatCode(() -> delivery.cancel(Optional.empty())).doesNotThrowAnyException();

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        }

        @Test
        void 완료된_배송이면_DELIVERY_ALREADY_COMPLETED이고_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.COMPLETED);

            assertAlreadyCompleted(() -> delivery.cancel(Optional.empty()));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.COMPLETED);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"IN_TRANSIT", "ARRIVED_AT_HUB", "DELIVERING"})
        void 허브를_출발한_배송은_409이고_그대로다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            assertInvalidTransition(() -> delivery.cancel(Optional.empty()), DELIVERY_CANCEL_MESSAGE);

            assertThat(delivery.getDeliveryStatus()).isEqualTo(current);
        }
    }

    @Nested
    class 취소_경로_기록_있음 {

        @Test
        void 배송과_경로_기록이_READY면_둘_다_취소한다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

            delivery.cancel(Optional.of(routeLog));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.CANCELLED);
        }

        @Test
        void 배송이_READY여도_경로_기록이_출발했으면_경로_기록_에러이고_둘_다_그대로다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.IN_TRANSIT);

            assertInvalidTransition(() -> delivery.cancel(Optional.of(routeLog)), ROUTE_LOG_CANCEL_MESSAGE);

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
        }

        // ORD-22: 완료 검사가 경로 기록 검사보다 먼저여야 order가 주문을 COMPLETED로 맞출 수 있다
        @Test
        void 완료된_배송이면_경로_기록_에러가_아니라_DELIVERY_ALREADY_COMPLETED이고_둘_다_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.COMPLETED);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.ARRIVED);

            assertAlreadyCompleted(() -> delivery.cancel(Optional.of(routeLog)));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.COMPLETED);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.ARRIVED);
        }

        @Test
        void 이미_취소된_배송이면_예외_없이_둘_다_그대로다() {
            Delivery delivery = deliveryIn(DeliveryStatus.CANCELLED);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.CANCELLED);

            assertThatCode(() -> delivery.cancel(Optional.of(routeLog))).doesNotThrowAnyException();

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.CANCELLED);
        }

        // 어긋난 데이터: 배송 검사에서 실패하면 경로 기록도 취소되지 않는다
        @Test
        void 경로_기록이_READY여도_배송이_출발했으면_배송_에러이고_경로_기록은_READY로_남는다() {
            Delivery delivery = deliveryIn(DeliveryStatus.IN_TRANSIT);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

            assertInvalidTransition(() -> delivery.cancel(Optional.of(routeLog)), DELIVERY_CANCEL_MESSAGE);

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
        }
    }

    @Nested
    class 삭제 {

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"COMPLETED", "CANCELLED"})
        void 끝난_배송은_삭제한다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            delivery.delete(Optional.empty(), MASTER_ID);

            assertThat(delivery.getDeletedAt()).isNotNull();
            assertThat(delivery.getDeletedBy()).isEqualTo(MASTER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"READY", "IN_TRANSIT", "ARRIVED_AT_HUB", "DELIVERING"})
        void 진행_중인_배송은_409이고_삭제하지_않는다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            assertInvalidTransition(() -> delivery.delete(Optional.empty(), MASTER_ID));

            assertThat(delivery.getDeletedAt()).isNull();
            assertThat(delivery.getDeletedBy()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"READY", "IN_TRANSIT", "ARRIVED_AT_HUB", "DELIVERING"})
        void 진행_중인_배송은_경로_기록이_끝났어도_409이고_둘_다_삭제하지_않는다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.ARRIVED);

            assertInvalidTransition(() -> delivery.delete(Optional.of(routeLog), MASTER_ID),
                    "진행 중인 배송은 삭제할 수 없습니다.");

            assertThat(delivery.getDeletedAt()).isNull();
            assertThat(routeLog.getDeletedAt()).isNull();
        }

        @Test
        void 완료된_배송은_도착한_경로_기록과_함께_요청자_기준으로_삭제한다() {
            Delivery delivery = deliveryIn(DeliveryStatus.COMPLETED);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.ARRIVED);

            delivery.delete(Optional.of(routeLog), MASTER_ID);

            assertThat(delivery.getDeletedBy()).isEqualTo(MASTER_ID);
            assertThat(routeLog.getDeletedAt()).isNotNull();
            assertThat(routeLog.getDeletedBy()).isEqualTo(MASTER_ID);
        }

        @Test
        void 취소된_배송은_취소된_경로_기록과_함께_요청자_기준으로_삭제한다() {
            Delivery delivery = deliveryIn(DeliveryStatus.CANCELLED);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.CANCELLED);

            delivery.delete(Optional.of(routeLog), MASTER_ID);

            assertThat(delivery.getDeletedBy()).isEqualTo(MASTER_ID);
            assertThat(routeLog.getDeletedBy()).isEqualTo(MASTER_ID);
        }

        // 어긋난 데이터를 MASTER가 배송 삭제로 정리할 수 있도록 경로 기록 상태는 보지 않는다
        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"COMPLETED", "CANCELLED"})
        void 배송이_끝났으면_경로_기록이_READY여도_함께_삭제한다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);
            DeliveryRouteLog routeLog = routeLogIn(delivery, RouteStatus.READY);

            delivery.delete(Optional.of(routeLog), MASTER_ID);

            assertThat(delivery.getDeletedBy()).isEqualTo(MASTER_ID);
            assertThat(routeLog.getDeletedAt()).isNotNull();
            assertThat(routeLog.getDeletedBy()).isEqualTo(MASTER_ID);
        }
    }

    // 서비스의 404 확인을 지나 들어온 경우를 막는 안전장치. 응답은 서비스 404와 같다
    @Nested
    class 다른_배송의_경로_기록 {

        @Test
        void 상태_변경은_404이고_둘_다_그대로다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery(false), RouteStatus.READY);

            assertRouteLogNotFound(() -> delivery.changeRouteStatus(routeLog, RouteStatus.IN_TRANSIT, null));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
        }

        @Test
        void 취소는_404이고_둘_다_그대로다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery(false), RouteStatus.READY);

            assertRouteLogNotFound(() -> delivery.cancel(Optional.of(routeLog)));

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
        }

        @Test
        void 삭제는_404이고_둘_다_삭제하지_않는다() {
            Delivery delivery = deliveryIn(DeliveryStatus.COMPLETED);
            DeliveryRouteLog routeLog = routeLogIn(deliveryIn(DeliveryStatus.COMPLETED), RouteStatus.ARRIVED);

            assertRouteLogNotFound(() -> delivery.delete(Optional.of(routeLog), MASTER_ID));

            assertThat(delivery.getDeletedAt()).isNull();
            assertThat(routeLog.getDeletedAt()).isNull();
        }

        @Test
        void 경로_담당자_지정은_404이고_담당자가_그대로다() {
            Delivery delivery = delivery(false);
            DeliveryRouteLog routeLog = routeLogIn(delivery(false), RouteStatus.READY);

            assertRouteLogNotFound(() -> delivery.assignRouteManager(routeLog, OTHER_ID));

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(ROUTE_MANAGER_ID);
        }
    }

    @Nested
    class 주문자_확인 {

        @Test
        void 주문자_본인이면_true() {
            assertThat(delivery(false).isOrderedBy(ORDERER_ID)).isTrue();
        }

        @Test
        void 다른_회원이면_false() {
            assertThat(delivery(false).isOrderedBy(OTHER_ID)).isFalse();
        }

        @Test
        void 주문자가_없는_배송이면_false() {
            Delivery delivery = Delivery.builder()
                    .orderId(UUID.randomUUID())
                    .departureHubId(SEOUL_HUB_ID)
                    .destinationHubId(BUSAN_HUB_ID)
                    .ordererId(null)
                    .build();

            assertThat(delivery.isOrderedBy(ORDERER_ID)).isFalse();
        }
    }

    private static void assertInvalidTransition(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION));
    }

    private static void assertInvalidTransition(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    private static void assertAlreadyCompleted(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DELIVERY_ALREADY_COMPLETED));
    }

    private static void assertRouteLogNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                    assertThat(e.getDetails()).extracting("field").containsExactly("routeLogId");
                });
    }

    // 경로 기록의 소유 확인이 id로 비교하므로 id를 채운다
    private static Delivery delivery(boolean sameHub) {
        Delivery delivery = Delivery.builder()
                .orderId(UUID.randomUUID())
                .departureHubId(SEOUL_HUB_ID)
                .destinationHubId(sameHub ? SEOUL_HUB_ID : BUSAN_HUB_ID)
                .destinationCompanyId(UUID.randomUUID())
                .deliveryManagerId(MANAGER_ID)
                .ordererId(ORDERER_ID)
                .address("부산시 해운대구")
                .recipientName("수령인")
                .build();
        ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
        return delivery;
    }

    // 다른 허브 배송. IN_TRANSIT·ARRIVED_AT_HUB·CANCELLED는 경로 기록 없이 만들 수 없어 reflection으로 정하고,
    // DELIVERING·COMPLETED는 ARRIVED_AT_HUB에서 실제 전이로 만든다 (startedAt·completedAt 기록)
    private static Delivery deliveryIn(DeliveryStatus status) {
        Delivery delivery = delivery(false);
        switch (status) {
            case READY -> { }
            case IN_TRANSIT, ARRIVED_AT_HUB, CANCELLED ->
                    ReflectionTestUtils.setField(delivery, "deliveryStatus", status);
            case DELIVERING, COMPLETED -> {
                ReflectionTestUtils.setField(delivery, "deliveryStatus", DeliveryStatus.ARRIVED_AT_HUB);
                delivery.changeStatus(DeliveryStatus.DELIVERING);
                if (status == DeliveryStatus.COMPLETED) {
                    delivery.changeStatus(DeliveryStatus.COMPLETED);
                }
            }
        }
        return delivery;
    }

    // 같은 허브 배송: READY → DELIVERING → COMPLETED, READY → CANCELLED
    private static Delivery sameHubDeliveryIn(DeliveryStatus status) {
        Delivery delivery = delivery(true);
        switch (status) {
            case READY -> { }
            case CANCELLED -> ReflectionTestUtils.setField(delivery, "deliveryStatus", status);
            case DELIVERING -> delivery.changeStatus(DeliveryStatus.DELIVERING);
            case COMPLETED -> {
                delivery.changeStatus(DeliveryStatus.DELIVERING);
                delivery.changeStatus(DeliveryStatus.COMPLETED);
            }
            default -> throw new IllegalArgumentException("같은 허브 배송은 " + status + "가 될 수 없습니다.");
        }
        return delivery;
    }

    // 상태는 reflection으로 정한다. 출발한 경로 기록(IN_TRANSIT·ARRIVED)은 출발 시각도 채운다
    private static DeliveryRouteLog routeLogIn(Delivery delivery, RouteStatus status) {
        DeliveryRouteLog routeLog = DeliveryRouteLog.builder()
                .delivery(delivery)
                .hubRouteId(UUID.randomUUID())
                .deliveryManagerId(ROUTE_MANAGER_ID)
                .expectedDistanceKm(new BigDecimal("320.00"))
                .expectedDurationMinutes(240)
                .build();
        ReflectionTestUtils.setField(routeLog, "routeStatus", status);
        if (status == RouteStatus.IN_TRANSIT || status == RouteStatus.ARRIVED) {
            ReflectionTestUtils.setField(routeLog, "departedAt", LocalDateTime.now().minusMinutes(90));
        }
        return routeLog;
    }
}
