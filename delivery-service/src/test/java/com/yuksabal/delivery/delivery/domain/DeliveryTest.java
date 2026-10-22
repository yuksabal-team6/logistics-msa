package com.yuksabal.delivery.delivery.domain;

import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static com.yuksabal.delivery.global.exception.DeliveryErrorCode.INVALID_STATUS_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 배송 엔티티의 상태 전이·담당자 지정·취소·삭제 규칙. 상태는 실제 전이 메서드로 만든다
class DeliveryTest {

    private static final UUID SEOUL_HUB_ID = UUID.randomUUID();
    private static final UUID BUSAN_HUB_ID = UUID.randomUUID();
    private static final UUID ORDERER_ID = UUID.randomUUID();
    private static final UUID MANAGER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();

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
    class 취소 {

        @Test
        void READY면_취소한다() {
            Delivery delivery = delivery(false);

            delivery.cancel();

            assertThat(delivery.getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            assertThat(delivery.isCancelled()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "READY")
        void READY가_아니면_409(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            assertInvalidTransition(delivery::cancel);

            assertThat(delivery.getDeliveryStatus()).isEqualTo(current);
        }
    }

    @Nested
    class 삭제 {

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"COMPLETED", "CANCELLED"})
        void 끝난_배송은_삭제한다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            delivery.delete(MASTER_ID);

            assertThat(delivery.getDeletedAt()).isNotNull();
            assertThat(delivery.getDeletedBy()).isEqualTo(MASTER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = DeliveryStatus.class, names = {"READY", "IN_TRANSIT", "ARRIVED_AT_HUB", "DELIVERING"})
        void 진행_중인_배송은_409이고_삭제하지_않는다(DeliveryStatus current) {
            Delivery delivery = deliveryIn(current);

            assertInvalidTransition(() -> delivery.delete(MASTER_ID));

            assertThat(delivery.getDeletedAt()).isNull();
            assertThat(delivery.getDeletedBy()).isNull();
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

    private static Delivery delivery(boolean sameHub) {
        return Delivery.builder()
                .orderId(UUID.randomUUID())
                .departureHubId(SEOUL_HUB_ID)
                .destinationHubId(sameHub ? SEOUL_HUB_ID : BUSAN_HUB_ID)
                .destinationCompanyId(UUID.randomUUID())
                .deliveryManagerId(MANAGER_ID)
                .ordererId(ORDERER_ID)
                .address("부산시 해운대구")
                .recipientName("수령인")
                .build();
    }

    // 다른 허브 배송: READY → IN_TRANSIT → ARRIVED_AT_HUB → DELIVERING → COMPLETED, READY → CANCELLED
    private static Delivery deliveryIn(DeliveryStatus status) {
        Delivery delivery = delivery(false);
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

    // 같은 허브 배송: READY → DELIVERING → COMPLETED, READY → CANCELLED
    private static Delivery sameHubDeliveryIn(DeliveryStatus status) {
        Delivery delivery = delivery(true);
        switch (status) {
            case READY -> { }
            case CANCELLED -> delivery.cancel();
            case DELIVERING -> delivery.changeStatus(DeliveryStatus.DELIVERING);
            case COMPLETED -> {
                delivery.changeStatus(DeliveryStatus.DELIVERING);
                delivery.changeStatus(DeliveryStatus.COMPLETED);
            }
            default -> throw new IllegalArgumentException("같은 허브 배송은 " + status + "가 될 수 없습니다.");
        }
        return delivery;
    }
}
