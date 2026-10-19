package com.yuksabal.delivery.routelog.entity;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.entity.DeliveryStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 경로 기록(허브→허브) 상태 전이와 배송 상태 연동, 담당자 지정 규칙
class DeliveryRouteLogTest {

    private static final UUID MANAGER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final BigDecimal DISTANCE_KM = new BigDecimal("325.50");

    @Nested
    class 허브_출발 {

        @Test
        void READY에서_출발하면_배송도_IN_TRANSIT이_된다() {
            DeliveryRouteLog routeLog = routeLog();

            routeLog.changeStatus(RouteStatus.IN_TRANSIT, null);

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getDepartedAt()).isNotNull();
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"IN_TRANSIT", "ARRIVED", "CANCELLED"})
        void READY가_아니면_409이고_배송_상태도_그대로다(RouteStatus current) {
            DeliveryRouteLog routeLog = routeLogIn(current);
            DeliveryStatus deliveryStatus = routeLog.getDelivery().getDeliveryStatus();

            assertInvalidTransition(() -> routeLog.changeStatus(RouteStatus.IN_TRANSIT, null));

            assertThat(routeLog.getRouteStatus()).isEqualTo(current);
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(deliveryStatus);
        }

        @Test
        void 배송이_READY가_아니면_409이고_경로_기록은_READY로_남는다() {
            Delivery delivery = delivery();
            delivery.cancel();
            DeliveryRouteLog routeLog = routeLog(delivery);

            assertInvalidTransition(() -> routeLog.changeStatus(RouteStatus.IN_TRANSIT, null));

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(routeLog.getDepartedAt()).isNull();
        }
    }

    @Nested
    class 허브_도착 {

        @Test
        void IN_TRANSIT에서_도착하면_실제_거리를_기록하고_배송은_ARRIVED_AT_HUB가_된다() {
            DeliveryRouteLog routeLog = routeLogIn(RouteStatus.IN_TRANSIT);

            routeLog.changeStatus(RouteStatus.ARRIVED, DISTANCE_KM);

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.ARRIVED);
            assertThat(routeLog.getArrivedAt()).isNotNull();
            assertThat(routeLog.getActualDistanceKm()).isEqualByComparingTo(DISTANCE_KM);
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(DeliveryStatus.ARRIVED_AT_HUB);
        }

        @Test
        void 실제_소요_시간은_출발과_도착_시각의_차이로_계산한다() {
            DeliveryRouteLog routeLog = routeLogIn(RouteStatus.IN_TRANSIT);
            // 출발 시각은 now()로 기록되므로 90분 전에 출발한 것으로 바꾼다
            ReflectionTestUtils.setField(routeLog, "departedAt", routeLog.getDepartedAt().minusMinutes(90));

            routeLog.changeStatus(RouteStatus.ARRIVED, DISTANCE_KM);

            assertThat(routeLog.getActualDurationMinutes()).isEqualTo(90);
        }

        // 거리 검증을 배송 상태 변경보다 먼저 하는 것이 의도 (400이면 경로 기록과 배송 모두 그대로)
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "-1.5"})
        void 실제_거리가_없거나_0_이하면_400이고_경로_기록과_배송_모두_그대로다(BigDecimal actualDistanceKm) {
            DeliveryRouteLog routeLog = routeLogIn(RouteStatus.IN_TRANSIT);

            assertThatThrownBy(() -> routeLog.changeStatus(RouteStatus.ARRIVED, actualDistanceKm))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly("actualDistanceKm");
                    });

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getArrivedAt()).isNull();
            assertThat(routeLog.getActualDistanceKm()).isNull();
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @Test
        void IN_TRANSIT이_아니면_거리가_없어도_400보다_409가_먼저다() {
            DeliveryRouteLog routeLog = routeLog();

            assertInvalidTransition(() -> routeLog.changeStatus(RouteStatus.ARRIVED, null));

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"READY", "ARRIVED", "CANCELLED"})
        void IN_TRANSIT이_아니면_409이고_배송_상태도_그대로다(RouteStatus current) {
            DeliveryRouteLog routeLog = routeLogIn(current);
            DeliveryStatus deliveryStatus = routeLog.getDelivery().getDeliveryStatus();

            assertInvalidTransition(() -> routeLog.changeStatus(RouteStatus.ARRIVED, DISTANCE_KM));

            assertThat(routeLog.getRouteStatus()).isEqualTo(current);
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(deliveryStatus);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RouteStatus.class, names = {"READY", "CANCELLED"})
    void 이_API로_바꿀_수_없는_상태를_요청하면_409(RouteStatus target) {
        DeliveryRouteLog routeLog = routeLog();

        assertInvalidTransition(() -> routeLog.changeStatus(target, DISTANCE_KM));

        assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
        assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Nested
    class 담당자_지정 {

        @Test
        void READY면_지정한다() {
            DeliveryRouteLog routeLog = routeLog();

            routeLog.assignManager(OTHER_ID);

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(OTHER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"IN_TRANSIT", "ARRIVED", "CANCELLED"})
        void 허브를_출발했거나_끝난_경로_기록은_409이고_담당자가_그대로다(RouteStatus current) {
            DeliveryRouteLog routeLog = routeLogIn(current);

            assertInvalidTransition(() -> routeLog.assignManager(OTHER_ID));

            assertThat(routeLog.getDeliveryManagerId()).isEqualTo(MANAGER_ID);
        }
    }

    private static void assertInvalidTransition(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION));
    }

    private static Delivery delivery() {
        return Delivery.builder()
                .orderId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .destinationHubId(UUID.randomUUID())
                .destinationCompanyId(UUID.randomUUID())
                .ordererId(UUID.randomUUID())
                .address("부산시 해운대구")
                .recipientName("수령인")
                .build();
    }

    private static DeliveryRouteLog routeLog() {
        return routeLog(delivery());
    }

    private static DeliveryRouteLog routeLog(Delivery delivery) {
        return DeliveryRouteLog.builder()
                .delivery(delivery)
                .hubRouteId(UUID.randomUUID())
                .deliveryManagerId(MANAGER_ID)
                .expectedDistanceKm(new BigDecimal("320.00"))
                .expectedDurationMinutes(240)
                .build();
    }

    // READY → IN_TRANSIT → ARRIVED, READY → CANCELLED (배송도 함께 취소)
    private static DeliveryRouteLog routeLogIn(RouteStatus status) {
        DeliveryRouteLog routeLog = routeLog();
        switch (status) {
            case READY -> { }
            case IN_TRANSIT -> routeLog.changeStatus(RouteStatus.IN_TRANSIT, null);
            case ARRIVED -> {
                routeLog.changeStatus(RouteStatus.IN_TRANSIT, null);
                routeLog.changeStatus(RouteStatus.ARRIVED, DISTANCE_KM);
            }
            case CANCELLED -> {
                routeLog.cancel();
                routeLog.getDelivery().cancel();
            }
        }
        return routeLog;
    }
}
