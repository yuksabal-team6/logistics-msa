package com.yuksabal.order.order.entity;

// order_status. FAILED = 생성 실패, CANCELLED = 사용자 취소 (결정 #19, #21)
public enum OrderStatus {
    PENDING, CONFIRMED, FAILED, CANCELLED, COMPLETED
}
