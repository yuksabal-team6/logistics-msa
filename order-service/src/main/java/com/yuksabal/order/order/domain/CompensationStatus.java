package com.yuksabal.order.order.domain;

// compensation_status. 주문 실패 시 재고 복원(보상) 진행 상태 (결정 #21)
public enum CompensationStatus {
    NOT_REQUIRED, PENDING, COMPLETED, FAILED
}
