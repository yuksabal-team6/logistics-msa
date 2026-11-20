package com.yuksabal.delivery.delivery.domain;

import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #302 취소 표식(Delivery.cancelledMarker)과 일반 배송 생성자의 필수 값 검증
class DeliveryCancelledMarkerTest {

    private static final UUID ORDER_ID = UUID.randomUUID();

    @Nested
    class 표식 {

        @Test
        void 같은_order_id의_CANCELLED_배송이고_허브_업체_주소_수령인은_없다() {
            Delivery marker = Delivery.cancelledMarker(ORDER_ID);

            assertThat(marker.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(marker.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            assertThat(marker.getDepartureHubId()).isNull();
            assertThat(marker.getDestinationHubId()).isNull();
            assertThat(marker.getDestinationCompanyId()).isNull();
            assertThat(marker.getAddress()).isNull();
            assertThat(marker.getRecipientName()).isNull();
        }

        @Test
        void 다시_취소하면_이미_취소된_배송이라_예외_없이_CANCELLED로_남는다() {
            Delivery marker = Delivery.cancelledMarker(ORDER_ID);

            assertThatCode(() -> marker.cancel(Optional.empty())).doesNotThrowAnyException();
            assertThat(marker.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        }

        // startDelivery()가 상태보다 허브를 먼저 비교하면 NPE (#302)
        @Test
        void DELIVERING_요청은_NPE가_아니라_INVALID_STATUS_TRANSITION() {
            Delivery marker = Delivery.cancelledMarker(ORDER_ID);

            assertInvalidTransition(() -> marker.changeStatus(DeliveryStatus.DELIVERING));
        }

        @Test
        void COMPLETED_요청은_INVALID_STATUS_TRANSITION() {
            Delivery marker = Delivery.cancelledMarker(ORDER_ID);

            assertInvalidTransition(() -> marker.changeStatus(DeliveryStatus.COMPLETED));
        }

        @Test
        void 담당자_지정은_INVALID_STATUS_TRANSITION() {
            Delivery marker = Delivery.cancelledMarker(ORDER_ID);

            assertInvalidTransition(() -> marker.assignManager(UUID.randomUUID()));
            assertThat(marker.getDeliveryManagerId()).isNull();
        }
    }

    @Nested
    class 일반_배송_필수_값 {

        @ParameterizedTest
        @ValueSource(strings = {"departureHubId", "destinationHubId", "destinationCompanyId", "address", "recipientName"})
        void 다섯_값_중_하나라도_null이면_VALIDATION_ERROR(String field) {
            Delivery.DeliveryBuilder builder = Delivery.builder()
                    .orderId(ORDER_ID)
                    .departureHubId(field.equals("departureHubId") ? null : UUID.randomUUID())
                    .destinationHubId(field.equals("destinationHubId") ? null : UUID.randomUUID())
                    .destinationCompanyId(field.equals("destinationCompanyId") ? null : UUID.randomUUID())
                    .address(field.equals("address") ? null : "부산시 해운대구")
                    .recipientName(field.equals("recipientName") ? null : "수령인");

            assertThatThrownBy(builder::build)
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly(field);
                    });
        }

        @Test
        void 선택_값_공급업체_주문자_담당자_Slack은_null이어도_READY로_만든다() {
            Delivery delivery = Delivery.builder()
                    .orderId(ORDER_ID)
                    .departureHubId(UUID.randomUUID())
                    .destinationHubId(UUID.randomUUID())
                    .destinationCompanyId(UUID.randomUUID())
                    .address("부산시 해운대구")
                    .recipientName("수령인")
                    .build();

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        }
    }

    private static void assertInvalidTransition(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(INVALID_STATUS_TRANSITION));
    }
}
