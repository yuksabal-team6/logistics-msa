package com.yuksabal.hub.inventory.entity;

import com.yuksabal.hub.hub.entity.Hub;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재고 엔티티 불변식. DTO 검증·DB 제약을 거치지 않고 만들어도 잘못된 재고는 생기지 않는다
class InventoryTest {

    private static final Hub HUB = Hub.builder().hubName("서울특별시 센터").address("서울특별시 송파구 송파대로 55").build();

    @Test
    void 수량을_넣지_않으면_0으로_만든다() {
        Inventory inventory = Inventory.builder().hub(HUB).productId(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        assertThat(inventory.getQuantity()).isZero();
    }

    @Test
    void 수량이_음수면_만들_수_없다() {
        assertThatThrownBy(() -> Inventory.builder().hub(HUB).productId(UUID.randomUUID()).companyId(UUID.randomUUID()).quantity(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고 수량은 0 이상이어야 합니다.");
    }

    @Test
    void 허브가_없으면_만들_수_없다() {
        assertThatThrownBy(() -> Inventory.builder().productId(UUID.randomUUID()).companyId(UUID.randomUUID()).quantity(10).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("재고의 허브는 필수입니다.");
    }

    @Test
    void 상품이_없으면_만들_수_없다() {
        assertThatThrownBy(() -> Inventory.builder().hub(HUB).companyId(UUID.randomUUID()).quantity(10).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("재고의 상품은 필수입니다.");
    }

    @Test
    void 업체가_없으면_만들_수_없다() {
        assertThatThrownBy(() -> Inventory.builder().hub(HUB).productId(UUID.randomUUID()).quantity(10).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("재고의 업체는 필수입니다.");
    }
}
