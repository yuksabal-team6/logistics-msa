package com.yuksabal.delivery.delivery.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.repository.DeliveryRouteLogRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.DELIVERY_ACCESS_DENIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

// 역할별 배송 상세·경로 기록 조회 권한. 경로 기록 조회는 DELIVERY가 배송 담당자도 주문자도 아닐 때만 한다
@ExtendWith(MockitoExtension.class)
class DeliveryReadPolicyTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID ORDERER_ID = UUID.randomUUID();
    private static final UUID DELIVERY_MANAGER_ID = UUID.randomUUID();
    private static final UUID ROUTE_MANAGER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    @Mock
    private DeliveryRouteLogRepository deliveryRouteLogRepository;

    @InjectMocks
    private DeliveryReadPolicy deliveryReadPolicy;

    @Test
    void MASTER는_누구의_배송이든_조회하고_경로_기록을_조회하지_않는다() {
        assertThatCode(() -> deliveryReadPolicy.checkReadable(delivery(), OTHER_ID, UserRole.MASTER))
                .doesNotThrowAnyException();

        verifyNoInteractions(deliveryRouteLogRepository);
    }

    @Nested
    class DELIVERY_역할 {

        @Test
        void 배송_담당자면_조회하고_경로_기록을_조회하지_않는다() {
            assertThatCode(() -> deliveryReadPolicy.checkReadable(delivery(), DELIVERY_MANAGER_ID, UserRole.DELIVERY))
                    .doesNotThrowAnyException();

            verifyNoInteractions(deliveryRouteLogRepository);
        }

        @Test
        void 주문자면_조회하고_경로_기록을_조회하지_않는다() {
            assertThatCode(() -> deliveryReadPolicy.checkReadable(delivery(), ORDERER_ID, UserRole.DELIVERY))
                    .doesNotThrowAnyException();

            verifyNoInteractions(deliveryRouteLogRepository);
        }

        @Test
        void 경로_기록_담당자면_조회한다() {
            Delivery delivery = delivery();
            given(deliveryRouteLogRepository.findByDeliveryId(DELIVERY_ID)).willReturn(Optional.of(routeLog(delivery)));

            assertThatCode(() -> deliveryReadPolicy.checkReadable(delivery, ROUTE_MANAGER_ID, UserRole.DELIVERY))
                    .doesNotThrowAnyException();
        }

        @Test
        void 배송_담당자도_주문자도_경로_기록_담당자도_아니면_403() {
            Delivery delivery = delivery();
            given(deliveryRouteLogRepository.findByDeliveryId(DELIVERY_ID)).willReturn(Optional.of(routeLog(delivery)));

            assertAccessDenied(() -> deliveryReadPolicy.checkReadable(delivery, OTHER_ID, UserRole.DELIVERY));
        }

        @Test
        void 경로_기록이_없는_배송이면_담당자나_주문자가_아닐_때_403() {
            given(deliveryRouteLogRepository.findByDeliveryId(DELIVERY_ID)).willReturn(Optional.empty());

            assertAccessDenied(() -> deliveryReadPolicy.checkReadable(delivery(), OTHER_ID, UserRole.DELIVERY));
        }

        @Test
        void 이미_조회한_경로_기록을_받으면_다시_조회하지_않는다() {
            Delivery delivery = delivery();

            assertThatCode(() -> deliveryReadPolicy.checkReadable(
                    delivery, Optional.of(routeLog(delivery)), ROUTE_MANAGER_ID, UserRole.DELIVERY))
                    .doesNotThrowAnyException();
            assertAccessDenied(() -> deliveryReadPolicy.checkReadable(
                    delivery, Optional.empty(), OTHER_ID, UserRole.DELIVERY));

            verifyNoInteractions(deliveryRouteLogRepository);
        }
    }

    @Nested
    class COMPANY_USER_역할 {

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"COMPANY", "USER"})
        void 주문자면_조회한다(UserRole role) {
            assertThatCode(() -> deliveryReadPolicy.checkReadable(delivery(), ORDERER_ID, role))
                    .doesNotThrowAnyException();

            verifyNoInteractions(deliveryRouteLogRepository);
        }

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"COMPANY", "USER"})
        void 주문자가_아니면_403이고_경로_기록을_조회하지_않는다(UserRole role) {
            assertAccessDenied(() -> deliveryReadPolicy.checkReadable(delivery(), OTHER_ID, role));

            verifyNoInteractions(deliveryRouteLogRepository);
        }

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"COMPANY", "USER"})
        void 배송_담당자로_지정된_id여도_주문자가_아니면_403(UserRole role) {
            assertAccessDenied(() -> deliveryReadPolicy.checkReadable(delivery(), DELIVERY_MANAGER_ID, role));

            verifyNoInteractions(deliveryRouteLogRepository);
        }
    }

    private static void assertAccessDenied(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DELIVERY_ACCESS_DENIED));
    }

    private static Delivery delivery() {
        Delivery delivery = Delivery.builder()
                .orderId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .destinationHubId(UUID.randomUUID())
                .destinationCompanyId(UUID.randomUUID())
                .deliveryManagerId(DELIVERY_MANAGER_ID)
                .ordererId(ORDERER_ID)
                .address("부산시 해운대구")
                .recipientName("수령인")
                .build();
        ReflectionTestUtils.setField(delivery, "id", DELIVERY_ID);
        return delivery;
    }

    private static DeliveryRouteLog routeLog(Delivery delivery) {
        return DeliveryRouteLog.builder()
                .delivery(delivery)
                .hubRouteId(UUID.randomUUID())
                .deliveryManagerId(ROUTE_MANAGER_ID)
                .expectedDistanceKm(new BigDecimal("320.00"))
                .expectedDurationMinutes(240)
                .build();
    }
}
