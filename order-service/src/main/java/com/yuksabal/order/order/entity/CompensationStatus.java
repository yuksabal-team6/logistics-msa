package com.yuksabal.order.order.entity;

// compensation_status. 주문 실패 시 보상(재고 복원·배송 취소) 진행 상태 (결정 #21)
// NOT_REQUIRED 되돌릴 것 없음 / PENDING 보상 필요·진행 중 / COMPLETED 완료 / FAILED 재시도까지 실패(수동 처리)
public enum CompensationStatus {
    NOT_REQUIRED, PENDING, COMPLETED, FAILED
}
