package com.yuksabal.hub.inventory.domain;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.hub.global.exception.HubErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재고 수량 규칙 (#282). 값이 바뀌면 새 객체를 돌려주고 원래 객체는 그대로다
class QuantityTest {

    @Test
    void 수량은_0이어도_된다() {
        assertThat(new Quantity(0).value()).isZero();
    }

    @Test
    void 수량이_음수면_만들_수_없다() {
        assertThatThrownBy(() -> new Quantity(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고 수량은 0 이상이어야 합니다.");
    }

    @Test
    void 값이_같으면_같은_수량이다() {
        assertThat(new Quantity(3)).isEqualTo(new Quantity(3));
    }

    @Nested
    class 더하기 {

        @Test
        void 더한_새_수량을_돌려주고_원래_수량은_그대로다() {
            Quantity before = new Quantity(7);

            Quantity after = before.plus(3);

            assertThat(after.value()).isEqualTo(10);
            assertThat(before.value()).isEqualTo(7);
        }

        @Test
        void 더할_수량이_1_미만이면_더할_수_없다() {
            assertThatThrownBy(() -> new Quantity(7).plus(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("변경 수량은 1 이상이어야 합니다.");
        }

        @Test
        void 정수_범위를_넘으면_예외다() {
            assertThatThrownBy(() -> new Quantity(Integer.MAX_VALUE).plus(1))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class 빼기 {

        @Test
        void 뺀_새_수량을_돌려주고_원래_수량은_그대로다() {
            Quantity before = new Quantity(10);

            Quantity after = before.minus(3);

            assertThat(after.value()).isEqualTo(7);
            assertThat(before.value()).isEqualTo(10);
        }

        @Test
        void 남은_수량과_같은_수량을_빼면_0이_된다() {
            assertThat(new Quantity(3).minus(3).value()).isZero();
        }

        @Test
        void 남은_수량이_부족하면_409와_현재_수량이다() {
            assertThatThrownBy(() -> new Quantity(2).minus(3))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(HubErrorCode.INSUFFICIENT_STOCK);
                        assertThat(e.getDetails()).extracting("reason").containsExactly("현재 재고는 2개입니다.");
                    });
        }

        @Test
        void 뺄_수량이_1_미만이면_뺄_수_없다() {
            assertThatThrownBy(() -> new Quantity(10).minus(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("변경 수량은 1 이상이어야 합니다.");
        }
    }

    @Test
    void 수량이_1_이상이면_재고가_있다() {
        assertThat(new Quantity(1).isPositive()).isTrue();
        assertThat(new Quantity(0).isPositive()).isFalse();
    }
}
