package com.yuksabal.hub.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재고 엔티티 불변식. DTO 검증·DB 제약을 거치지 않고 만들어도 잘못된 재고는 생기지 않는다
class InventoryTest {

    private static final UUID HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void 수량을_넣지_않으면_0으로_만든다() {
        Inventory inventory = Inventory.builder().hubId(HUB_ID).productId(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        assertThat(inventory.getQuantity()).isZero();
    }

    @Test
    void 수량이_음수면_만들_수_없다() {
        assertThatThrownBy(() -> Inventory.builder().hubId(HUB_ID).productId(UUID.randomUUID()).companyId(UUID.randomUUID()).quantity(-1).build())
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
        assertThatThrownBy(() -> Inventory.builder().hubId(HUB_ID).companyId(UUID.randomUUID()).quantity(10).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("재고의 상품은 필수입니다.");
    }

    @Test
    void 입고로_만들면_상품과_업체를_상품_정보에서_가져온다() {
        UUID productId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        Inventory inventory = Inventory.create(HUB_ID, new ProductInfo(productId, companyId, UUID.randomUUID(), true), 7);

        assertThat(inventory.getHubId()).isEqualTo(HUB_ID);
        assertThat(inventory.getProductId()).isEqualTo(productId);
        assertThat(inventory.getCompanyId()).isEqualTo(companyId);
        assertThat(inventory.getQuantity()).isEqualTo(7);
    }

    @Test
    void 업체가_없으면_만들_수_없다() {
        assertThatThrownBy(() -> Inventory.builder().hubId(HUB_ID).productId(UUID.randomUUID()).quantity(10).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("재고의 업체는 필수입니다.");
    }
}
