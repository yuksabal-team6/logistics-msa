package com.yuksabal.order.order.event;

/**
 * 주문 이벤트 타입을 정의한다.
 * slack 알림 종류와 1:1이다.
 */
public enum OrderEventType {
    // 주문 확정 → 주문 접수 알림 (#order-noti, 주문자 멘션)
    ORDER_CONFIRMED,
    // 보상 실패 → 수동 처리 필요 알림 (#order-admin-noti)
    MANUAL_ACTION_REQUIRED,
    // 예상하지 못한 오류, 보상은 완료 → 확인 필요 알림 (#order-admin-noti)
    CHECK_NEEDED
}
