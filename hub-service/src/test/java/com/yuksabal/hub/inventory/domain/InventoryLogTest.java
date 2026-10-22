package com.yuksabal.hub.inventory.domain;

import com.yuksabal.hub.hub.domain.Hub;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재고 로그 생성 규칙. 로그는 상황별 정적 팩토리로만 만들고, 팩토리가 변경 유형·orderId·변화량을 정한다
// 수량은 변화량이라 0 이상이고, 방향은 changeType으로 구분한다
class InventoryLogTest {

    private static final UUID ORDER_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");

    private static final Inventory INVENTORY = inventory(UUID.fromString("50000000-0000-4000-8000-000000000001"));

    @Nested
    class 입고 {

        @Test
        void 주문_없이_INBOUND로_만든다() {
            InventoryLog log = InventoryLog.inbound(INVENTORY, 5);

            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.INBOUND);
            assertThat(log.getOrderId()).isNull();
            assertThat(log.getQuantity()).isEqualTo(5);
        }
    }

    @Nested
    class 주문_차감 {

        @Test
        void 주문_ID와_함께_DECREASE로_만든다() {
            InventoryLog log = InventoryLog.decrease(INVENTORY, ORDER_ID, 3);

            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.DECREASE);
            assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(log.getQuantity()).isEqualTo(3);
        }

        @Test
        void 주문_ID가_없으면_만들_수_없다() {
            assertThatThrownBy(() -> InventoryLog.decrease(INVENTORY, null, 3))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("주문 차감 로그는 주문 ID가 필수입니다.");
        }
    }

    @Nested
    class 주문_복원 {

        @Test
        void 주문_ID와_함께_RESTORE로_만든다() {
            InventoryLog log = InventoryLog.restore(INVENTORY, ORDER_ID, 3);

            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.RESTORE);
            assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(log.getQuantity()).isEqualTo(3);
        }

        @Test
        void 차감_기록_없이_온_복원은_수량_0으로_만든다() {
            assertThat(InventoryLog.restore(INVENTORY, ORDER_ID, 0).getQuantity()).isZero();
        }

        @Test
        void 주문_ID가_없으면_만들_수_없다() {
            assertThatThrownBy(() -> InventoryLog.restore(INVENTORY, null, 3))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("주문 복원 로그는 주문 ID가 필수입니다.");
        }
    }

    @Nested
    class 관리자_조정 {

        @Test
        void 늘리면_ADJUST_INCREASE이고_변화량은_차이다() {
            InventoryLog log = InventoryLog.adjust(INVENTORY, 10, 15);

            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.ADJUST_INCREASE);
            assertThat(log.getQuantity()).isEqualTo(5);
            assertThat(log.getOrderId()).isNull();
        }

        @Test
        void 줄이면_ADJUST_DECREASE이고_변화량은_차이의_절댓값이다() {
            InventoryLog log = InventoryLog.adjust(INVENTORY, 10, 4);

            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.ADJUST_DECREASE);
            assertThat(log.getQuantity()).isEqualTo(6);
        }

        @Test
        void 조정_전후_수량이_같으면_만들_수_없다() {
            assertThatThrownBy(() -> InventoryLog.adjust(INVENTORY, 10, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("조정 전후 수량이 같으면 조정 로그를 만들 수 없습니다.");
        }
    }

    @Nested
    class 불변식 {

        @Test
        void 수량이_음수면_만들_수_없다() {
            assertThatThrownBy(() -> InventoryLog.restore(INVENTORY, ORDER_ID, -3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("로그 수량은 0 이상이어야 합니다.");
        }

        @Test
        void 재고가_없으면_만들_수_없다() {
            assertThatThrownBy(() -> InventoryLog.inbound(null, 5))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("로그의 재고는 필수입니다.");
        }
    }

    @Nested
    class 재고_일치 {

        @Test
        void 같은_재고면_true() {
            InventoryLog log = InventoryLog.decrease(INVENTORY, ORDER_ID, 3);

            assertThat(log.isFor(inventory(INVENTORY.getId()))).isTrue();
        }

        @Test
        void 다른_재고면_false() {
            InventoryLog log = InventoryLog.decrease(INVENTORY, ORDER_ID, 3);

            assertThat(log.isFor(inventory(UUID.randomUUID()))).isFalse();
        }
    }

    private static Inventory inventory(UUID inventoryId) {
        Inventory inventory = Inventory.builder()
                .hub(Hub.builder().hubName("서울특별시 센터").address("서울특별시 송파구 송파대로 55").build())
                .productId(UUID.randomUUID())
                .companyId(UUID.randomUUID())
                .quantity(10)
                .build();
        ReflectionTestUtils.setField(inventory, "id", inventoryId);
        return inventory;
    }
}
