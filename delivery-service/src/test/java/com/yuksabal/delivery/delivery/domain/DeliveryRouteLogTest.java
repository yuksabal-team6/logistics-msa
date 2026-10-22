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
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 경로 기록(허브→허브) 자체의 상태 전이와 담당자 지정 규칙. 배송 상태 연동은 DeliveryTest(changeRouteStatus)에서 확인한다
class DeliveryRouteLogTest {

    private static final UUID MANAGER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final BigDecimal DISTANCE_KM = new BigDecimal("325.50");

    @Nested
    class 허브_출발 {

        @Test
        void READY에서_출발하고_배송_상태는_바꾸지_않는다() {
            DeliveryRouteLog routeLog = routeLog();

            routeLog.depart();

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getDepartedAt()).isNotNull();
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"IN_TRANSIT", "ARRIVED", "CANCELLED"})
        void READY가_아니면_409이고_상태가_그대로다(RouteStatus current) {
            DeliveryRouteLog routeLog = routeLogIn(current);

            assertInvalidTransition(routeLog::depart);

            assertThat(routeLog.getRouteStatus()).isEqualTo(current);
        }
    }

    @Nested
    class 허브_도착 {

        @Test
        void IN_TRANSIT에서_도착하면_실제_거리를_기록하고_배송_상태는_바꾸지_않는다() {
            DeliveryRouteLog routeLog = routeLogIn(RouteStatus.IN_TRANSIT);

            routeLog.arrive(DISTANCE_KM);

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.ARRIVED);
            assertThat(routeLog.getArrivedAt()).isNotNull();
            assertThat(routeLog.getActualDistanceKm()).isEqualByComparingTo(DISTANCE_KM);
            assertThat(routeLog.getDelivery().getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }

        @Test
        void 실제_소요_시간은_출발과_도착_시각의_차이로_계산한다() {
            DeliveryRouteLog routeLog = routeLogIn(RouteStatus.IN_TRANSIT);
            // 출발 시각을 90분 전으로 바꾼다
            ReflectionTestUtils.setField(routeLog, "departedAt", LocalDateTime.now().minusMinutes(90));

            routeLog.arrive(DISTANCE_KM);

            assertThat(routeLog.getActualDurationMinutes()).isEqualTo(90);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"0", "-1.5"})
        void 실제_거리가_없거나_0_이하면_400이고_그대로다(BigDecimal actualDistanceKm) {
            DeliveryRouteLog routeLog = routeLogIn(RouteStatus.IN_TRANSIT);

            assertThatThrownBy(() -> routeLog.arrive(actualDistanceKm))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly("actualDistanceKm");
                    });

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.IN_TRANSIT);
            assertThat(routeLog.getArrivedAt()).isNull();
            assertThat(routeLog.getActualDistanceKm()).isNull();
        }

        @Test
        void IN_TRANSIT이_아니면_거리가_없어도_400보다_409가_먼저다() {
            DeliveryRouteLog routeLog = routeLog();

            assertInvalidTransition(() -> routeLog.arrive(null));

            assertThat(routeLog.getRouteStatus()).isEqualTo(RouteStatus.READY);
        }

        @ParameterizedTest
        @EnumSource(value = RouteStatus.class, names = {"READY", "ARRIVED", "CANCELLED"})
        void IN_TRANSIT이_아니면_409이고_상태가_그대로다(RouteStatus current) {
            DeliveryRouteLog routeLog = routeLogIn(current);

            assertInvalidTransition(() -> routeLog.arrive(DISTANCE_KM));

            assertThat(routeLog.getRouteStatus()).isEqualTo(current);
        }
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
        return DeliveryRouteLog.builder()
                .delivery(delivery())
                .hubRouteId(UUID.randomUUID())
                .deliveryManagerId(MANAGER_ID)
                .expectedDistanceKm(new BigDecimal("320.00"))
                .expectedDurationMinutes(240)
                .build();
    }

    // 상태는 reflection으로 정한다. 출발한 경로 기록(IN_TRANSIT·ARRIVED)은 출발 시각도 채운다
    private static DeliveryRouteLog routeLogIn(RouteStatus status) {
        DeliveryRouteLog routeLog = routeLog();
        ReflectionTestUtils.setField(routeLog, "routeStatus", status);
        if (status == RouteStatus.IN_TRANSIT || status == RouteStatus.ARRIVED) {
            ReflectionTestUtils.setField(routeLog, "departedAt", LocalDateTime.now());
        }
        return routeLog;
    }
}
