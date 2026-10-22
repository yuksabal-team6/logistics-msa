package com.yuksabal.delivery.delivery.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

// delivery_status. 같은 허브 배송은 READY → DELIVERING → COMPLETED (결정 #16)
public enum DeliveryStatus {
    READY, IN_TRANSIT, ARRIVED_AT_HUB, DELIVERING, COMPLETED, CANCELLED;

    // 끝난 상태만 나열한다. 그 외는 모두 진행 중 (새 상태가 추가되면 기본이 진행 중 → 배송 삭제·회원 탈퇴가 막히는 쪽)
    private static final Set<DeliveryStatus> FINISHED = EnumSet.of(COMPLETED, CANCELLED);
    private static final Set<DeliveryStatus> IN_PROGRESS =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(FINISHED)));

    public boolean isInProgress() {
        return !FINISHED.contains(this);
    }

    // 쿼리 조건용 (existsBy...DeliveryStatusIn)
    public static Set<DeliveryStatus> inProgressStatuses() {
        return IN_PROGRESS;
    }
}
