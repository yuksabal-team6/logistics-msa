package com.yuksabal.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 진행 중 기준. 배송 삭제·회원 탈퇴 확인(existsBy...DeliveryStatusIn)이 이 기준을 쓴다
class DeliveryStatusTest {

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"COMPLETED", "CANCELLED"})
    void 끝난_상태는_진행_중이_아니다(DeliveryStatus status) {
        assertThat(status.isInProgress()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"COMPLETED", "CANCELLED"})
    void 끝난_상태가_아니면_진행_중이다(DeliveryStatus status) {
        assertThat(status.isInProgress()).isTrue();
    }

    @Test
    void 쿼리용_진행_중_목록은_isInProgress와_같다() {
        assertThat(DeliveryStatus.inProgressStatuses())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(DeliveryStatus.values()).filter(DeliveryStatus::isInProgress).toList());
    }

    @Test
    void 쿼리용_진행_중_목록은_수정할_수_없다() {
        assertThatThrownBy(() -> DeliveryStatus.inProgressStatuses().add(DeliveryStatus.COMPLETED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
