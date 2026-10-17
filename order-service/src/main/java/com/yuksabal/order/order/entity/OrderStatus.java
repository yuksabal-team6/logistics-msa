package com.yuksabal.order.order.entity;

public enum OrderStatus {
    PENDING, // 주문 생성 진행 중 (기본값)
    CONFIRMED, // 주문 확정 (재고 차감 + 배송 생성 모두 성공)
    FAILED, // 생성 실패 (재고 부족 -> 복원 불필요 / 재고 차감 후 실패 -> 재고 복원 필요)
    CANCELLED, // 사용자 취소 (허브 출발 전 취소)
    COMPLETED // 배송 완료
}
