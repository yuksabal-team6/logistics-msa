package com.yuksabal.delivery.routelog.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

// route_status
public enum RouteStatus {
    READY, IN_TRANSIT, ARRIVED, CANCELLED;

    // 끝난 상태만 나열한다. 그 외는 모두 진행 중 (새 상태가 추가되면 기본이 진행 중 → 회원 탈퇴가 막히는 쪽)
    private static final Set<RouteStatus> FINISHED = EnumSet.of(ARRIVED, CANCELLED);
    private static final Set<RouteStatus> IN_PROGRESS =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(FINISHED)));

    public boolean isInProgress() {
        return !FINISHED.contains(this);
    }

    // 쿼리 조건용 (existsBy...RouteStatusIn)
    public static Set<RouteStatus> inProgressStatuses() {
        return IN_PROGRESS;
    }
}
