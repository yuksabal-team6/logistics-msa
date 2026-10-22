package com.yuksabal.hub.inventory.domain;

import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// 입고 규칙: 활성 상품만, 상품 업체의 관리 허브에만 입고할 수 있다
class ProductInfoTest {

    private static final UUID HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void 활성_상품을_관리_허브에_입고하면_통과한다() {
        assertThatCode(() -> product(HUB_ID, true).checkInboundTo(HUB_ID)).doesNotThrowAnyException();
    }

    @Test
    void 비활성_상품이면_422() {
        assertThatThrownBy(() -> product(HUB_ID, false).checkInboundTo(HUB_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION);
                    assertThat(e.getMessage()).isEqualTo("비활성 상품은 입고할 수 없습니다.");
                    assertThat(e.getDetails()).extracting("field").containsExactly("productId");
                });
    }

    @Test
    void 관리_허브가_아니면_422이고_관리_허브를_알려준다() {
        assertThatThrownBy(() -> product(OTHER_HUB_ID, true).checkInboundTo(HUB_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION);
                    assertThat(e.getMessage()).isEqualTo("상품 업체의 관리 허브에만 입고할 수 있습니다.");
                    assertThat(e.getDetails()).extracting("field", "reason")
                            .containsExactly(tuple("hubId", "상품 업체의 관리 허브는 " + OTHER_HUB_ID + "입니다."));
                });
    }

    // 둘 다 어긋나면 비활성이 먼저다 (기존 입고 흐름의 검사 순서)
    @Test
    void 비활성이면서_관리_허브도_아니면_비활성으로_응답한다() {
        assertThatThrownBy(() -> product(OTHER_HUB_ID, false).checkInboundTo(HUB_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("비활성 상품은 입고할 수 없습니다."));
    }

    private static ProductInfo product(UUID managingHubId, boolean active) {
        return new ProductInfo(UUID.randomUUID(), UUID.randomUUID(), managingHubId, active);
    }
}
