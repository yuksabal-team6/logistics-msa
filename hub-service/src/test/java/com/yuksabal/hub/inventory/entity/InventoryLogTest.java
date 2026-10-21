package com.yuksabal.hub.inventory.entity;

import com.yuksabal.hub.hub.entity.Hub;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재고 로그 엔티티 불변식. 수량은 변화량이라 0 이상이고, 방향은 changeType으로 구분한다
class InventoryLogTest {

    private static final Inventory INVENTORY = Inventory.builder()
            .hub(Hub.builder().hubName("서울특별시 센터").address("서울특별시 송파구 송파대로 55").build())
            .productId(UUID.randomUUID())
            .companyId(UUID.randomUUID())
            .quantity(10)
            .build();

    @Test
    void 입고_로그는_주문_없이_만든다() {
        InventoryLog log = InventoryLog.builder().inventory(INVENTORY).changeType(InventoryChangeType.INBOUND).quantity(5).build();

        assertThat(log.getOrderId()).isNull();
        assertThat(log.getQuantity()).isEqualTo(5);
    }

    @Test
    void 차감_기록_없이_온_복원은_수량_0으로_만든다() {
        InventoryLog log = InventoryLog.builder()
                .inventory(INVENTORY).orderId(UUID.randomUUID()).changeType(InventoryChangeType.RESTORE).quantity(0).build();

        assertThat(log.getQuantity()).isZero();
    }

    @Test
    void 수량이_음수면_만들_수_없다() {
        assertThatThrownBy(() -> InventoryLog.builder().inventory(INVENTORY).changeType(InventoryChangeType.ADJUST_DECREASE).quantity(-3).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("로그 수량은 0 이상이어야 합니다.");
    }

    @Test
    void 수량이_없으면_만들_수_없다() {
        assertThatThrownBy(() -> InventoryLog.builder().inventory(INVENTORY).changeType(InventoryChangeType.INBOUND).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("로그의 수량은 필수입니다.");
    }

    @Test
    void 재고가_없으면_만들_수_없다() {
        assertThatThrownBy(() -> InventoryLog.builder().changeType(InventoryChangeType.INBOUND).quantity(5).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("로그의 재고는 필수입니다.");
    }

    @Test
    void 변경_유형이_없으면_만들_수_없다() {
        assertThatThrownBy(() -> InventoryLog.builder().inventory(INVENTORY).quantity(5).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("로그의 변경 유형은 필수입니다.");
    }
}
