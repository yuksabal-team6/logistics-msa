package com.yuksabal.order.order.entity;

// order_status (결정 #19, #21)
public enum OrderStatus {
    PENDING, // 주문 생성 완료, 기본값
    CONFIRMED, // 배송 생성 성공, (재고 차감 후 배송 생성됨, 배송 생성 실패 시 재고 복원 필요함)
    FAILED, // 생성 실패 (Pending -> 재고 부족인 경우 복원 불필요 / 재고 차감 후 배송 생성 실패 경우 재고 복원 필요함)
    CANCELLED, // 사용자 취소 (허브 출발 전 취소)
    COMPLETED // 정상 완료
}
