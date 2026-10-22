package com.yuksabal.hub.inventory.domain;

// inventory_change_type. DECREASE = 주문 차감, RESTORE = 주문 실패 복원, INBOUND = 입고,
// ADJUST_INCREASE·ADJUST_DECREASE = 관리자 수량 조정 (타입이 방향을 나타내고 수량은 항상 양수)
// 값을 추가하면 data.sql의 change_type CHECK 제약도 같이 수정 (ddl-auto update는 기존 CHECK 제약을 바꾸지 않는다)
public enum InventoryChangeType {
    DECREASE, RESTORE, INBOUND, ADJUST_INCREASE, ADJUST_DECREASE
}
