package com.yuksabal.delivery.routelog.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 진행 중 기준. 회원 탈퇴 확인(existsBy...RouteStatusIn)이 이 기준을 쓴다
class RouteStatusTest {

    @ParameterizedTest
    @EnumSource(value = RouteStatus.class, names = {"ARRIVED", "CANCELLED"})
    void 끝난_상태는_진행_중이_아니다(RouteStatus status) {
        assertThat(status.isInProgress()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = RouteStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ARRIVED", "CANCELLED"})
    void 끝난_상태가_아니면_진행_중이다(RouteStatus status) {
        assertThat(status.isInProgress()).isTrue();
    }

    @Test
    void 쿼리용_진행_중_목록은_isInProgress와_같다() {
        assertThat(RouteStatus.inProgressStatuses())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(RouteStatus.values()).filter(RouteStatus::isInProgress).toList());
    }

    @Test
    void 쿼리용_진행_중_목록은_수정할_수_없다() {
        assertThatThrownBy(() -> RouteStatus.inProgressStatuses().add(RouteStatus.ARRIVED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
